package io.github.skyshadowhero.fancypad;

import android.content.res.Resources;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/**
 * FancyPad · 摇晃放大光标（类似 macOS「摇晃鼠标指针以定位」），作用域 {@code system}。
 *
 * <p>做法是**只读观测**指针位置流，不拦截、不改写任何输入事件：
 *
 * <ol>
 *   <li>hook {@code com.android.server.wm.PointerEventDispatcher#onInputEvent(InputEvent)}
 *       —— 这是 WMS 绑在 display 的 monitor input channel 上的 {@code InputEventReceiver}，
 *       每个 pointer 类事件都会走一遍它注册的监听器，因此鼠标每次移动都在这里经过；</li>
 *   <li>只看鼠标（{@code SOURCE_MOUSE} / {@code TOOL_TYPE_MOUSE}）的
 *       {@code ACTION_MOVE} 与 {@code ACTION_HOVER_MOVE}，按「方向换向次数」判摇晃 ——
 *       需要凑够的换向次数由偏好控制（灵敏度），窗口与最小摆幅见下面的常量；</li>
 *   <li>判定命中 → 交给 {@link CursorHooks#onShake()} 抬高一档放大倍数（**一直摇会一直变大**），
 *       停手超过保持时长后再交给 {@link CursorHooks#onShakeIdle()} 缩回。</li>
 * </ol>
 *
 * <p><b>为什么不用 {@code setPointerScale()}</b>：框架的 {@code PointerIconCache} 自带
 * {@code setPointerScale(float)}，但它影响的是 {@code PointerIcon.getLoadedSystemIcon()} 内部
 * 的缩放；而本模块已经接管了 {@code getLoadedPointerIcon()}、不调用 {@code chain.proceed()}，
 * 框架那条缩放链路根本不会被执行。放大必须在模块自己的 {@code buildIcon()} 里做。
 *
 * <p><b>稳定性</b>：这是 system_server 的输入热路径。判定循环只读 volatile、不做分配；
 * 任何异常都在拦截器里就地吞掉 —— 异常漏出去会打断 WMS 的输入分发，
 * 而 {@code onInputEvent} 里紧跟着的 {@code finishInputEvent()} 一旦被跳过，
 * 后续输入事件会一起受影响。
 *
 * <p>总开关为 false（默认）时，整个过程只有两次 volatile 读就返回。
 */
public final class CursorShake {

    private static final String TAG = "FancyPad";

    /** 目标类：WMS 的指针事件分发器。 */
    private static final String CLS_DISPATCHER = "com.android.server.wm.PointerEventDispatcher";
    private static final String M_ON_INPUT_EVENT = "onInputEvent";
    private static final String CLS_INPUT_EVENT = "android.view.InputEvent";

    /** 一次「有效摆动」的最小累计位移（dp）。低于它的来回不算摇晃，避免手抖误触发。 */
    private static final float MIN_SWING_DP = 24f;

    /** 这些换向必须落在这么长的窗口内；超时就算一次新的摇晃，重新数。 */
    private static final long WINDOW_MS = 1000L;

    /** 两帧间隔超过它就认为手停了：清掉摆动累计，避免把停顿前后的两段拼成一次摇晃。 */
    private static final long GAP_RESET_MS = 300L;

    /** 放大态最短保持时间兜底，避免偏好读到异常值时反复重载光标。 */
    private static final long MIN_HOLD_MS = 200L;

    private final XposedInterface module;
    private final CursorHooks owner;

    private volatile boolean installed;

    /** 诊断用：第一帧鼠标移动只记一次日志（只在事件线程上读写，不需要同步）。 */
    private boolean firstEventLogged;

    /** 最小摆幅换算成像素（装 hook 时算一次，热路径上直接用）。 */
    private volatile float minSwingPx = MIN_SWING_DP;

    // ---- 摇晃判定状态：只在指针事件线程（WMS 的 UiThread）上读写 ----
    private final Axis axisX = new Axis();
    private final Axis axisY = new Axis();
    /** 0 表示「还没收到过第一帧」，用它来区分「首次」与「mtime 恰好为 0」。 */
    private long lastEventMs;
    private long firstReversalMs;
    private int reversalsInWindow;
    private float lastX;
    private float lastY;

    // ---- 放大态：事件线程置位，调度线程复位 ----
    private final Object boostLock = new Object();
    private boolean boosting;
    /** 最近一次命中摇晃的时间（事件时间，与 {@link SystemClock#uptimeMillis()} 同一基准）。 */
    private long lastShakeMs;
    private final AtomicBoolean releaseScheduled = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;

    public CursorShake(XposedInterface module, CursorHooks owner) {
        this.module = module;
        this.owner = owner;
    }

    // ------------------------------------------------------------ 安装

    /** 由 {@link CursorHooks#installSystemHooks(ClassLoader)} 在 system_server 里调用。可重复调用。 */
    public synchronized void install(ClassLoader cl) {
        if (installed) {
            return;
        }
        try {
            Class<?> cls = cl.loadClass(CLS_DISPATCHER);
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (M_ON_INPUT_EVENT.equals(m.getName()) && m.getParameterCount() == 1
                        && CLS_INPUT_EVENT.equals(m.getParameterTypes()[0].getName())) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                Log.w(TAG, "event=shake_hook_missing reason=onInputEvent_not_found");
                return;
            }
            try {
                target.setAccessible(true);
            } catch (Throwable ignored) {
            }
            module.hook(target)
                    .setId("pointer_event_dispatcher")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        // 纯观测钩子：自己吞掉全部异常，只调用一次 proceed 并原样返回结果。
                        try {
                            observe(chain.getArg(0));
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
            minSwingPx = computeMinSwingPx();
            installed = true;
            Log.i(TAG, "event=shake_hook_installed target=" + CLS_DISPATCHER + "#" + M_ON_INPUT_EVENT);
        } catch (Throwable t) {
            Log.w(TAG, "event=shake_hook_failed", t);
        }
    }

    /** 最小摆幅换算成像素。取系统默认 density：光标本身也是按它渲染的。 */
    private static float computeMinSwingPx() {
        try {
            float density = Resources.getSystem().getDisplayMetrics().density;
            if (density > 0f) {
                return MIN_SWING_DP * density;
            }
        } catch (Throwable ignored) {
        }
        return MIN_SWING_DP;
    }

    // -------------------------------------------------------- 摇晃判定

    private void observe(Object arg) {
        // 功能没开就一行返回。两个 volatile 读，热路径上不留别的开销。
        if (!(arg instanceof MotionEvent) || !HookPrefs.cursorShakeEnabled()
                || !HookPrefs.cursorEnabled()) {
            return;
        }
        MotionEvent event = (MotionEvent) arg;
        if (!isMouse(event)) {
            return;
        }
        int action = event.getActionMasked();
        // 鼠标移动：按住键时是 ACTION_MOVE，没按键悬停移动时是 ACTION_HOVER_MOVE。
        if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_HOVER_MOVE) {
            return;
        }
        if (!firstEventLogged) {
            // 只记一次：用来确认指针事件确实到达本模块、且 raw 坐标是有效的。
            // 如果日志里始终没有这一行，说明 hook 没命中（检查 scope/重启）；坐标恒为 0 则说明
            // 这条通道拿不到 raw 坐标，摇晃判定会失效。
            firstEventLogged = true;
            Log.i(TAG, "event=shake_first_mouse_move action=" + action
                    + " x=" + event.getRawX() + " y=" + event.getRawY()
                    + " source=0x" + Integer.toHexString(event.getSource()));
        }
        feed(event.getEventTime(), event.getRawX(), event.getRawY());
    }

    /** 只认鼠标/触控板；触控笔与手指不算（它们没有「摇晃找光标」的语义）。 */
    private static boolean isMouse(MotionEvent event) {
        try {
            if ((event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
                return true;
            }
            return event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void feed(long t, float x, float y) {
        if (lastEventMs == 0L) {
            // 第一帧只用来定位，不参与判定。
            lastEventMs = t;
            lastX = x;
            lastY = y;
            return;
        }
        if (t - lastEventMs > GAP_RESET_MS) {
            resetMotion();
        }
        float dx = x - lastX;
        float dy = y - lastY;
        lastEventMs = t;
        lastX = x;
        lastY = y;

        float minSwing = minSwingPx;
        // 两个轴都要喂：X 命中了不代表可以跳过 Y 的状态维护。
        boolean reversedX = axisX.feed(dx, minSwing);
        boolean reversedY = axisY.feed(dy, minSwing);
        if (reversedX || reversedY) {
            onReversal(t);
        }
    }

    private void resetMotion() {
        axisX.reset();
        axisY.reset();
        reversalsInWindow = 0;
        firstReversalMs = 0L;
    }

    /** 累计窗口内的换向次数，够了就放大。 */
    private void onReversal(long t) {
        if (reversalsInWindow == 0 || t - firstReversalMs > WINDOW_MS) {
            firstReversalMs = t;
            reversalsInWindow = 1;
            return;
        }
        reversalsInWindow++;
        // 门槛由偏好控制（灵敏度），每次判定时读 volatile，改设置立刻生效
        if (reversalsInWindow < HookPrefs.cursorShakeReversals()) {
            return;
        }
        reversalsInWindow = 0;
        firstReversalMs = 0L;
        boost(t);
    }

    // ------------------------------------------------------------ 放大

    /**
     * 一次摇晃命中。
     *
     * <p>放大倍数的「阶梯」由 {@link CursorHooks} 维护：首次给配置的倍数，之后每命中一次就再往上
     * 抬一档，所以一直摇会一直变大。这里只负责判定命中与保持时长。
     */
    private void boost(long eventMs) {
        if (!(HookPrefs.cursorShakeBoost() > 1f)) {
            return;                     // 倍数是 1（无放大）就没什么可做
        }
        synchronized (boostLock) {
            lastShakeMs = eventMs;
            boosting = true;
        }
        Log.i(TAG, "event=shake_detected");
        owner.onShake();
        scheduleRelease(HookPrefs.cursorShakeHoldMs());
    }

    private void scheduleRelease(long delayMs) {
        if (!releaseScheduled.compareAndSet(false, true)) {
            return;                     // 已经排队，由那一轮自己判断是否顺延
        }
        try {
            scheduler().schedule(this::onReleaseTick, Math.max(MIN_HOLD_MS, delayMs),
                    TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            releaseScheduled.set(false);
        }
    }

    private void onReleaseTick() {
        long now = SystemClock.uptimeMillis();
        long hold = Math.max(MIN_HOLD_MS, HookPrefs.cursorShakeHoldMs());
        boolean release = false;
        long remaining = 0L;
        synchronized (boostLock) {
            releaseScheduled.set(false);
            if (!boosting) {
                return;
            }
            long idle = Math.max(0L, now - lastShakeMs);
            if (idle >= hold) {
                boosting = false;
                release = true;
            } else {
                remaining = hold - idle;
            }
        }
        if (release) {
            Log.i(TAG, "event=shake_released");
            owner.onShakeIdle();
        } else {
            scheduleRelease(remaining); // 期间又被摇了：顺延
        }
    }

    private ScheduledExecutorService scheduler() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            return s;
        }
        synchronized (this) {
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "dsh-cursor-shake");
                    t.setDaemon(true);
                    return t;
                });
            }
            return scheduler;
        }
    }

    // -------------------------------------------------------------- 内嵌

    /** 单个轴上的摆动累计：方向不变就继续累加，方向一变换向一次。 */
    private static final class Axis {
        /** 当前运动方向：-1 / 0 / +1 */
        private int dir;
        /** 当前方向上的累计位移 */
        private float accum;

        void reset() {
            dir = 0;
            accum = 0f;
        }

        /**
         * 喂一帧位移。
         *
         * @return 本次是否构成一次「满足最小摆幅的换向」
         */
        boolean feed(float delta, float minSwing) {
            if (delta == 0f) {
                return false;
            }
            int next = delta > 0f ? 1 : -1;
            if (next == dir) {
                accum += delta;
                return false;
            }
            // 方向变了：先看刚才那一段够不够长，再开始累计新方向。
            boolean reversed = dir != 0 && Math.abs(accum) >= minSwing;
            dir = next;
            accum = delta;
            return reversed;
        }
    }
}
