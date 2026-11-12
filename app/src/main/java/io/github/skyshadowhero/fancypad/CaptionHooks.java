package io.github.skyshadowhero.fancypad;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/**
 * FancyPad · 小窗控制器（作用域 {@code com.android.systemui}）。
 *
 * <p>HyperOS 平板上，窗口顶部居中那三个小圆点（无障碍名 "Window control bar"）点一下会弹出
 * 一个按钮条，里面是 全屏 / 分屏 / 小窗 / 新窗口 / 关闭。这个按钮条由
 * {@code Miui-WindowManager-Shell.jar} 里的
 * {@code ...miuiwindowdecor.handlemenu.MiuiCaptionContainerView} <b>纯代码</b>搭出来
 * （没有 layout XML）。本域一共做四件事：
 *
 * <ol>
 *   <li><b>关闭按钮 × → −</b>：{@code createStateButton(int padLR, int padTB, int drawableRes)}
 *       是每个按钮设图标的地方，第三个实参等于 {@code R.drawable.caption_close} 就是关闭键。
 *       把它子 View 的 background 换成 {@link FrameWithMinusDrawable}（圆角外框 + 横线）。</li>
 *   <li><b>追加 / 隐藏 / 重排按钮</b>：都挂在
 *       {@code MiuiDecorationDot.addMouseHoverEffect(ViewGroup, Point, OnClickListener)} ——
 *       这是框架**统一给按钮条里每个子 View 挂点击监听**的地方（遍历子 View 调
 *       {@code setOnClickListener}）。等它跑完再动：追加的按钮不会被框架监听覆盖，
 *       隐藏（{@code removeViewAt}）和重排（按偏好稳定排序后重新挂载）也不会破坏已挂好的监听。
 *       隐藏 / 顺序见偏好 {@code caption_button_hidden} / {@code caption_button_order}。</li>
 *   <li><b>菜单窗口宽度跟着按钮数走，定位仍用 MIUI 的</b>：{@code createHandleMenu} 是按
 *       {@code 按钮宽×个数 + 内边距×…} 算好宽高才调 {@code addWindow} 的，
 *       按钮集合变了就会被 {@code setWindowCrop} 裁掉 / 留白。所以给 bar 打个标记，
 *       hook {@code addWindow} 按**实际子 View** 重算宽度。x 不另挑基准 ——
 *       MIUI 给的 x 本身就是「以三个控制点为中心」算出来的，只按宽度差值挪半个差值，
 *       宽度没变时 x 原样不动。枢轴（第 6 个参数）同步反向补偿，出现动画仍从中间长出。</li>
 * </ol>
 *
 * <p>「彻底关闭」分两步收尾：先 {@code IActivityTaskManager.removeTask(taskId)} 把任务摘掉
 * （最近任务 / 底栏的**卡片才会消失**），再用 {@code IActivityManager.forceStopPackage(pkg, userId)}
 * —— 系统「应用信息 → 强制停止」走的那个 API —— 杀进程。见 {@link #killApp}。
 *
 * <p>为什么不走资源覆盖（RRO）：{@code caption_close} 在
 * {@code MiuiWMShellResources-install.apk} 里，RRO 能换但需要重启、且做不成开关。
 */
public final class CaptionHooks {

    private static final String CLS_CONTAINER =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.handlemenu"
                    + ".MiuiCaptionContainerView";
    private static final String CLS_FRAME_LAYOUT =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.handlemenu"
                    + ".MiuiCaptionFrameLayout";
    private static final String CLS_STATE_BUTTON =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.handlemenu"
                    + ".MiuiCaptionStateButton";
    private static final String CLS_DOT =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration"
                    + ".MiuiDecorationDot";
    /** 控制点条上那三个圆点（{@code onDraw} 里画三个 circle）。 */
    private static final String CLS_DOT_VIEW =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration"
                    + ".MiuiDecorationDotView";
    private static final String CLS_R_DRAWABLE = "com.android.wm.shell.R$drawable";
    private static final String CLS_R_ID = "com.android.wm.shell.R$id";
    /** OS4 多分屏模式的顶栏小窗菜单（另一套视图，但复用同一套 R.id.state_*）。 */
    private static final String CLS_MULTI_SPLIT_CONTROLLER =
            "com.android.wm.shell.multiplesplit.ui.menu.MultipleSplitTopMenuController";
    /**
     * 多分屏顶栏菜单的按钮：{@code setIcon(int)} 里 new 一个 View、设 background 再
     * addViewInLayout —— 换 × → − 就 hook 它，和普通小窗的 {@code createStateButton}
     * 是对称的两条路径。
     */
    private static final String CLS_MULTI_SPLIT_BUTTON =
            "com.android.wm.shell.multiplesplit.ui.menu.MultipleSplitTopMenuButton";

    /** 框架「关闭」按钮的图标资源名（见 MiuiCaptionContainerView.init 里的 state_close）。 */
    private static final String CLOSE_ICON_NAME = "caption_close";

    /** 框架「新窗口」按钮的图标资源名（补按钮时复用）。 */
    private static final String NEW_WINDOW_ICON_NAME = "caption_newwindow";

    /** 普通按钮的按下高亮底（框架给 newwindow / close 用的就是它）。 */
    private static final String PRESS_SELECTOR_NAME = "caption_selector_press";

    /** {@code WindowConfiguration.WINDOWING_MODE_*} 里我们要判的两个。 */
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    /** 框架「关闭」按钮的 id 名（R.id.state_close）。 */
    private static final String FRAMEWORK_CLOSE_ID_NAME = "state_close";

    /**
     * 框架按钮的 {@code R.id 名 → PrefKeys.CB_*} 对照表。
     *
     * <p>顺序就是 {@code MiuiCaptionContainerView.init} 的添加顺序。用它把按钮认出来，
     * 才能按偏好隐藏 / 重排。
     */
    private static final String[][] FRAMEWORK_BUTTONS = {
            {"state_fullscrren", PrefKeys.CB_FULLSCREEN},
            {"state_appcasting", PrefKeys.CB_CASTING},
            {"state_splitleftortop", PrefKeys.CB_SPLIT_LEFT},
            {"state_splitrightorbottom", PrefKeys.CB_SPLIT_RIGHT},
            {"state_freeform", PrefKeys.CB_FREEFORM},
            {"state_newwindow", PrefKeys.CB_NEW_WINDOW},
            {FRAMEWORK_CLOSE_ID_NAME, PrefKeys.CB_CLOSE},
            // OS4 新增：多分屏（框架类名 MiuiExtendModeButton，id 直接挂在自己身上）
            {"state_extend_mode", PrefKeys.CB_EXTEND_MODE},
    };

    /**
     * OS4 多分屏顶栏菜单的 {@code R.id → PrefKeys.CB_*} 对照表。
     *
     * <p>它复用普通小窗那套 {@code state_*} id，但**语义并不一一对应**：
     * 多分屏菜单里 {@code state_splitleftortop} 那个按钮其实是「多分屏」入口
     * （{@code addMultipleSplitButton} 里硬编码 0x700a02d8），跟普通小窗的「分屏·左/上」
     * 是两个东西。早先共用一张表，结果用户把「分屏·左/上」一关，多分屏菜单的
     * 「多分屏」按钮就被一并删掉（表现就是「按钮消失了，切一下总开关又回来」）。
     *
     * <p>所以这里单独建表：多分屏入口归到 {@link PrefKeys#CB_EXTEND_MODE}
     * （设置页里那一项就叫「多分屏」），其余按语义直译。
     */
    private static final String[][] MULTI_SPLIT_BUTTONS = {
            {"state_fullscrren", PrefKeys.CB_FULLSCREEN},
            {"state_appcasting", PrefKeys.CB_CASTING},
            {"state_splitleftortop", PrefKeys.CB_EXTEND_MODE},
            {"state_freeform", PrefKeys.CB_FREEFORM},
            {"state_newwindow", PrefKeys.CB_NEW_WINDOW},
            {FRAMEWORK_CLOSE_ID_NAME, PrefKeys.CB_CLOSE},
    };

    /** 给按钮条打的标记，{@code addWindow} 靠它把那条 bar 找回来算宽度。 */
    private static final int BAR_TAG_KEY = 0x7E0F0002;
    private static final Object BAR_TAG_VALUE = new Object();

    /**
     * 图标 View 上记录「我们已经换上去的那个 drawable」的 tag key。
     *
     * <p>值就是 {@link FrameWithMinusDrawable} 实例本身（不是布尔标记）：这样既能判断
     * 换过没有，也能发现「tag 还在、背景已经被框架换回 ×」的情况。
     */
    private static final int MINUS_TAG_KEY = 0x7E0F0003;

    /**
     * 我们追加的按钮把「自己真正的点击监听」存在这个 tag 上。
     * 框架的 {@code addMouseHoverEffect} 会遍历按钮条给每个子 View 重设
     * {@code setOnClickListener}，所以 proceed 之后要按 tag 恢复回来。
     */
    private static final int LISTENER_TAG_KEY = 0x7E0F0004;

    /**
     * 我们那个红色「彻底关闭」按钮的 id（挂在包裹用的 FrameLayout 上）。
     * 只用来自查（判断菜单里到底有没有加进去，顺便算加宽量），不与 R.id 冲突。
     */
    private static final int FORCE_CLOSE_BUTTON_ID = 0x7E0F0001;

    /** 红色。HyperOS 的危险色偏 #E53935 这一档。 */
    private static final int FORCE_CLOSE_RED = 0xFFE53935;

    /**
     * 代点小米自己的「关闭」之后，等多久再杀进程。
     *
     * <p>必须等它的关闭转场播完 —— {@code MulWinSwitchAnimStarter.closeFullOrFreeform}
     * 走的是 {@code mMultiWinSwitchTransition.startCloseFullOrFreeform()}，是个
     * WindowContainerTransaction 转场（顺带做任务移除和最近任务刷新）。进程杀早了，
     * 窗口会在动画中途直接消失，就是看到的「闪一下」。
     */
    private static final long FORCE_KILL_DELAY_MS = 700L;

    /** 按钮内左右/上下留白的兜底值（dp），读不到现成按钮的 padding 时用。 */
    private static final float FALLBACK_PAD_LR_DP = 12f;
    private static final float FALLBACK_PAD_TB_DP = 8f;

    private final XposedInterface module;
    private final AtomicBoolean installed = new AtomicBoolean(false);

    // ---- install() 解析一次，供 hook 回调使用（进程内单例，不必一层层传参）----
    private Class<?> frameLayoutCls;
    private Class<?> stateButtonCls;
    /** 「关闭」按钮的图标 drawable（红色按钮拿它染色）。 */
    private int closeIconId;
    /** 框架「关闭」按钮的 id（红色按钮代点它）。 */
    private int closeButtonId;
    /** 框架「新窗口」按钮的 id 与图标（补按钮时复用框架自己的点击分发）。 */
    private int newWindowId;
    private int newWindowIconId;
    /** 普通按钮的按下高亮底。 */
    private int pressSelectorId;
    /**
     * 框架那个 {@code MiuiCaptionClickListener}（= {@code addMouseHoverEffect} 的第 3 个参数）。
     *
     * <p>红色「彻底关闭」靠**直接调它**来触发关闭 —— 这样即使「关闭」按钮被
     * 「按钮开关」隐藏甚至删掉，关闭动画照样有（代点按钮的话会落空）。
     */
    private volatile View.OnClickListener frameworkMenuClickListener;
    /** 调 {@link #frameworkMenuClickListener} 时冒充「关闭」按钮用的哑 View（只用到它的 id）。 */
    private View closeProxyView;
    /** 框架按钮 id → key 的对照表：普通小窗菜单与 OS4 多分屏顶栏菜单共用。 */
    private volatile Map<Integer, String> idToKey = new HashMap<>();
    /** 多分屏顶栏菜单单独一份（同一批 id，语义不同，见 {@link #MULTI_SPLIT_BUTTONS}）。 */
    private volatile Map<Integer, String> multiSplitIdToKey = new HashMap<>();

    public CaptionHooks(XposedInterface module) {
        this.module = module;
    }

    /**
     * 由 {@code XposedEntry} 在 {@code com.android.systemui} 进程里调用。
     *
     * @return true = 已装好（含「本来就装过」）；false = 目标还没就绪，可以重试。
     *
     * <p>只有**预检**（资源 ID + 两个类）失败才返回 false：那是「SystemUI 还没走到这一步」
     * 或版本不符，晚一点重试有意义。预检通过后各 hook 自己兜异常，装完就置位 ——
     * 之后再重试会把同一个方法重复 Hook 一遍，反而危险。
     */
    public synchronized boolean install(ClassLoader classLoader) {
        if (installed.get()) {
            return true;
        }
        final int closeIcon;
        final Class<?> frameCls;
        final Class<?> stateCls;
        try {
            closeIcon = resolveResId(classLoader, CLS_R_DRAWABLE, CLOSE_ICON_NAME);
            if (closeIcon == 0) {
                L.INSTANCE.w("event=caption_hook_not_ready reason=drawable_not_resolved name="
                        + CLOSE_ICON_NAME);
                return false;
            }
            frameCls = classLoader.loadClass(CLS_FRAME_LAYOUT);
            stateCls = classLoader.loadClass(CLS_STATE_BUTTON);
        } catch (Throwable t) {
            L.INSTANCE.w("event=caption_hook_not_ready msg=" + t);
            return false;
        }

        installed.set(true);
        closeIconId = closeIcon;
        frameLayoutCls = frameCls;
        stateButtonCls = stateCls;
        try {
            newWindowIconId = resolveResId(classLoader, CLS_R_DRAWABLE, NEW_WINDOW_ICON_NAME);
            pressSelectorId = resolveResId(classLoader, CLS_R_DRAWABLE, PRESS_SELECTOR_NAME);

            // 框架按钮 id → key 的对照表（普通菜单 / 多分屏顶栏菜单各一张）
            idToKey.clear();
            for (String[] pair : FRAMEWORK_BUTTONS) {
                int id = resolveResId(classLoader, CLS_R_ID, pair[0]);
                if (id == 0) {
                    continue;
                }
                idToKey.put(id, pair[1]);
                if (PrefKeys.CB_CLOSE.equals(pair[1])) {
                    closeButtonId = id;
                } else if (PrefKeys.CB_NEW_WINDOW.equals(pair[1])) {
                    newWindowId = id;
                }
            }
            multiSplitIdToKey.clear();
            for (String[] pair : MULTI_SPLIT_BUTTONS) {
                int id = resolveResId(classLoader, CLS_R_ID, pair[0]);
                if (id != 0) {
                    multiSplitIdToKey.put(id, pair[1]);
                }
            }

            installCloseIconHook(classLoader, closeIconId);
            installMultiSplitCloseIconHook(classLoader, closeIconId);
            installMenuButtonsHook(classLoader, idToKey);
            installMultipleSplitMenuHook(classLoader);
            installWidenMenuHook(classLoader);
            installMultipleSplitWidenHook(classLoader);
            installHideDotsHook(classLoader);

            L.INSTANCE.i("event=caption_hooks_installed closeIcon=0x"
                    + Integer.toHexString(closeIconId)
                    + " closeId=0x" + Integer.toHexString(closeButtonId)
                    + " newWindowId=0x" + Integer.toHexString(newWindowId));
        } catch (Throwable t) {
            L.INSTANCE.e("event=hook_failed target=caption_container", t);
        }
        return true;
    }

    // ------------------------------------------------------------------ 1) × → −

    private void installCloseIconHook(ClassLoader cl, final int closeIconId) {
        Method createStateButton = findMethod(cl, CLS_CONTAINER, "createStateButton", 3);
        if (createStateButton == null) {
            L.INSTANCE.w("event=caption_hook_skipped reason=createStateButton_not_found");
            return;
        }
        try {
            createStateButton.setAccessible(true);
            module.hook(createStateButton)
                    .setId("caption_create_state_button")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        // 先跑原实现拿到按钮，再决定要不要换图；换图失败不能影响原结果。
                        Object result = chain.proceed();
                        try {
                            if (HookPrefs.captionEnabled()
                                    && HookPrefs.captionCloseAsMinus()
                                    && isArg(chain, 2, closeIconId)) {
                                swapIconToMinus(result);
                            }
                        } catch (Throwable t) {
                            L.INSTANCE.w("event=caption_icon_swap_failed msg=" + t.getMessage());
                        }
                        return result;
                    });
        } catch (Throwable t) {
            L.INSTANCE.e("event=hook_failed target=caption_create_state_button", t);
        }
    }

    /**
     * 按钮结构：MiuiCaptionStateButton(FrameLayout) → 唯一子 View，图标就是那个子 View 的
     * background（见 {@code createStateButton}：{@code view.setBackgroundResource(i3)} +
     * {@code addViewInLayout(view, -1, new FrameLayout.LayoutParams(-2, -2))}）。
     */
    private static void swapIconToMinus(Object button) {
        swapIconDrawable(firstChildOf(button));
    }

    /**
     * 把图标 View 的 × 换成 −（外框保留，字形改横线）。
     *
     * <p>幂等判断**不看「打过 tag」，而是看 tag 里记的 drawable 是不是当前这层背景**：
     * 框架有时会在我们换完之后又 {@code setBackgroundResource} 一次（重建图标 / 切换状态），
     * 这时 tag 还在、背景却已经不是我们的了。只认 tag 会漏掉，表现就是「图标不见了」。
     * 反过来，背景本来就是我们的 drawable 时直接跳过，不会套娃。
     */
    private static void swapIconDrawable(View icon) {
        if (icon == null) {
            return;
        }
        if (icon instanceof ViewGroup && ((ViewGroup) icon).getChildCount() > 0) {
            return;  // 有子 View 的是容器，不是图标叶子
        }
        Drawable original = icon.getBackground();
        if (original instanceof FrameWithMinusDrawable) {
            icon.setTag(MINUS_TAG_KEY, original);
            return;
        }
        int w = original == null ? 0 : original.getIntrinsicWidth();
        int h = original == null ? 0 : original.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            // 少数图标 drawable 没给 intrinsic 尺寸，退回 View 的实测尺寸
            w = icon.getWidth();
            h = icon.getHeight();
        }
        if (original == null || w <= 0 || h <= 0) {
            return;
        }
        FrameWithMinusDrawable swapped = new FrameWithMinusDrawable(original, w, h, isNight(icon));
        icon.setBackground(swapped);
        icon.setTag(MINUS_TAG_KEY, swapped);
        L.INSTANCE.i("event=caption_icon_swapped from=" + w + "x" + h);
    }

    /**
     * 把按钮里**所有**图标子 View 都换成 −。
     *
     * <p>{@code MultipleSplitTopMenuButton.setIcon(int)} 的实现是「new 一个 View → addViewInLayout」，
     * 从不清理旧子 View。按钮一旦被复用（关掉菜单再打开就是这样），旧图标留在下面、新的又叠一层，
     * 只换索引 {@code before} 那一个的话，下面那层还是 ×。整组换掉最省心。
     */
    private static void swapIconGroup(ViewGroup button) {
        boolean swappedAny = false;
        for (int i = 0; i < button.getChildCount(); i++) {
            View child = button.getChildAt(i);
            if (child.getBackground() == null) {
                continue;
            }
            swapIconDrawable(child);
            swappedAny = true;
        }
        if (!swappedAny) {
            // 结构变了（图标不是子 View 而是按钮自己的背景）时的兜底
            swapIconToMinus(button);
        }
    }

    /**
     * OS4 多分屏顶栏菜单的 × → −。普通小窗走 {@code createStateButton}，这套走
     * {@code MultipleSplitTopMenuButton.setIcon(int)}：它在方法里 new 一个 View、
     * setBackgroundResource(iconRes) 后 addViewInLayout(..., -1, lp)。
     *
     * <p>proceed 之后把按钮里**所有**图标子 View 一起换掉（这个方法不清理旧子 View，
     * 按钮复用时会叠出多层图标），不依赖子 View 顺序。
     */
    private void installMultiSplitCloseIconHook(ClassLoader cl, final int closeIconId) {
        Method setIcon = findMethod(cl, CLS_MULTI_SPLIT_BUTTON, "setIcon", 1);
        if (setIcon == null) {
            L.INSTANCE.w("event=caption_hook_skipped reason=multi_split_setIcon_not_found");
            return;
        }
        try {
            setIcon.setAccessible(true);
            module.hook(setIcon)
                    .setId("caption_multi_split_set_icon")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        Object self = chain.getThisObject();
                        Object result = chain.proceed();
                        try {
                            if (HookPrefs.captionEnabled()
                                    && HookPrefs.captionCloseAsMinus()
                                    && isArg(chain, 0, closeIconId)
                                    && self instanceof ViewGroup) {
                                // setIcon 每次都是 new 一个 View 追加进来（不清理旧子 View），
                                // 所以不能只换「刚新增的那个」：按钮被复用时旧的 × 还留在下面，
                                // 新的一次又叠上来。整组一起换才稳。
                                swapIconGroup((ViewGroup) self);
                            }
                        } catch (Throwable t) {
                            L.INSTANCE.w("event=caption_multi_split_icon_swap_failed msg="
                                    + t.getMessage());
                        }
                        return result;
                    });
        } catch (Throwable t) {
            L.INSTANCE.e("event=hook_failed target=caption_multi_split_set_icon", t);
        }
    }

    // ------------------------------------- 2) 菜单按钮：追加 / 隐藏 / 重排
    //
    // 都挂在 MiuiDecorationDot.addMouseHoverEffect 上 —— 它是框架**统一给按钮条里每个
    // 子 View 挂点击监听 + 小米私有 hover 属性**的地方。所以按钮必须在 chain.proceed()
    // **之前**追加，让框架把它当亲儿子一起处理；proceed 之后恢复我们自己的点击监听，
    // 再做隐藏 / 重排。顺序反过来（先 proceed 再追加）会缺私有属性，长按会把菜单关掉。
    private void installMenuButtonsHook(ClassLoader cl, final Map<Integer, String> idToKey) {
        Method addMouseHoverEffect =
                findMethod(cl, CLS_DOT, "addMouseHoverEffect", 3);
        if (addMouseHoverEffect == null) {
            L.INSTANCE.w("event=caption_hook_skipped reason=addMouseHoverEffect_not_found");
            return;
        }
        try {
            addMouseHoverEffect.setAccessible(true);
            module.hook(addMouseHoverEffect)
                    .setId("caption_add_mouse_hover_effect")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        ViewGroup bar = chain.getArg(0) instanceof ViewGroup
                                ? (ViewGroup) chain.getArg(0) : null;
                        Object dot = chain.getThisObject();
                        boolean active = HookPrefs.captionEnabled() && bar != null;
                        // ① 先追加：让框架的 addMouseHoverEffect 把私有视图属性 /
                        //    Folme hover 一并挂到我们的按钮上（长按一致性的关键）。
                        try {
                            if (active) {
                                // arg2 就是框架那个 MiuiCaptionClickListener，后面几个地方都要用
                                if (chain.getArg(2) instanceof View.OnClickListener) {
                                    frameworkMenuClickListener = (View.OnClickListener) chain.getArg(2);
                                }
                                // 打个标记：addWindow 靠它找回这条 bar 算宽度
                                bar.setTag(BAR_TAG_KEY, BAR_TAG_VALUE);
                                if (HookPrefs.captionForceClose()) {
                                    addForceCloseButton(bar, dot, true);
                                }
                                if (HookPrefs.captionAlwaysNewWindow()) {
                                    addNewWindowButton(bar, chain.getArg(2));
                                }
                            }
                        } catch (Throwable t) {
                            L.INSTANCE.w("event=caption_menu_button_failed msg=" + t.getMessage());
                        }
                        Object result = chain.proceed();
                        // ② 框架重设过所有子 View 的点击监听，按 tag 恢复我们的；再做显隐 / 排序。
                        try {
                            if (active) {
                                restoreOwnListeners(bar);
                                applyButtonConfig(bar, idToKey, dot);
                                ensureCloseMinus(bar);
                            }
                        } catch (Throwable t) {
                            L.INSTANCE.w("event=caption_menu_post_failed msg=" + t.getMessage());
                        }
                        return result;
                    });
        } catch (Throwable t) {
            L.INSTANCE.e("event=hook_failed target=caption_add_mouse_hover_effect", t);
        }
    }

    /**
     * 按偏好隐藏 / 重排按钮条里的按钮。
     *
     * <p>隐藏就是 {@code removeViewAt}；重排是把子 View 按 {@code caption_button_order}
     * 做**稳定**排序后重新挂载（点击监听挂在 View 上，跟着走）。
     *
     * <p>另外有个「隐藏当前状态对应的按钮」：全屏时再显示「全屏」没意义，小窗同理。
     */
    private static void applyButtonConfig(ViewGroup bar, Map<Integer, String> idToKey, Object dot) {
        List<String> hidden = splitKeys(HookPrefs.captionButtonHidden());
        if (HookPrefs.captionHideCurrentState()) {
            int mode = readIntField(dot, "mWindowingMode");
            if (mode == WINDOWING_MODE_FREEFORM) {
                hidden.add(PrefKeys.CB_FREEFORM);
            } else if (mode == WINDOWING_MODE_FULLSCREEN) {
                hidden.add(PrefKeys.CB_FULLSCREEN);
            }
        }
        if (!hidden.isEmpty()) {
            for (int i = bar.getChildCount() - 1; i >= 0; i--) {
                String key = keyOf(bar.getChildAt(i), idToKey);
                if (key != null && hidden.contains(key)) {
                    bar.removeViewAt(i);
                }
            }
        }
        final List<String> order = splitKeys(HookPrefs.captionButtonOrder());
        int count = bar.getChildCount();
        if (order.isEmpty() || count < 2) {
            return;
        }
        List<View> children = new ArrayList<>(count);
        Map<View, ViewGroup.LayoutParams> params = new HashMap<>();
        for (int i = 0; i < count; i++) {
            View child = bar.getChildAt(i);
            children.add(child);
            params.put(child, child.getLayoutParams());
        }
        List<View> sorted = new ArrayList<>(children);
        sorted.sort((a, b) -> Integer.compare(
                orderIndex(order, keyOf(a, idToKey)),
                orderIndex(order, keyOf(b, idToKey))));
        boolean changed = false;
        for (int i = 0; i < count; i++) {
            if (sorted.get(i) != children.get(i)) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            return;
        }
        bar.removeAllViews();
        for (View child : sorted) {
            bar.addView(child, params.get(child));
        }
    }

    /** 认出一个子 View 是哪个按钮。框架按钮的 id 挂在包裹里的 state button 上。 */
    private static String keyOf(View child, Map<Integer, String> idToKey) {
        if (child.getId() == FORCE_CLOSE_BUTTON_ID) {
            return PrefKeys.CB_FORCE_CLOSE;
        }
        if (child instanceof ViewGroup) {
            View inner = ((ViewGroup) child).getChildAt(0);
            if (inner != null) {
                String key = idToKey.get(inner.getId());
                if (key != null) {
                    return key;
                }
            }
        }
        return idToKey.get(child.getId());
    }

    private static List<String> splitKeys(String spec) {
        List<String> out = new ArrayList<>();
        if (spec == null) {
            return out;
        }
        for (String part : spec.split(",")) {
            String key = part.trim();
            if (!key.isEmpty()) {
                out.add(key);
            }
        }
        return out;
    }

    /** key 在顺序表里的下标；不在表里的排到最后。 */
    private static int orderIndex(List<String> order, String key) {
        if (key == null) {
            return Integer.MAX_VALUE;
        }
        int index = order.indexOf(key);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /** 在框架挂完监听之后，把红色按钮追加到同一条 bar 里。 */
    private void addForceCloseButton(ViewGroup bar, Object host, boolean useFrameworkProxy)
            throws Exception {
        Context ctx = bar.getContext();
        if (bar.findViewById(FORCE_CLOSE_BUTTON_ID) != null) {
            return; // 已经有就不重复加（同一条 bar 只会构造一次）
        }
        // OS4 多分屏顶栏菜单可能拿不到 RunningTaskInfo：按钮照样加，
        // 只是退化成「代点关闭」而不做强杀（见 onClick 末尾）。
        ActivityManager.RunningTaskInfo info = resolveTaskInfo(host);
        final String pkg = info == null ? null : packageOf(info);
        final int userId = info == null ? 0 : userIdOf(info);
        final int taskId = info == null ? -1 : info.taskId;
        // 多分屏顶栏菜单没有普通小窗那个全局 MiuiCaptionClickListener：在追加我们自己的
        // 按钮**之前**从框架按钮上把监听取出来，否则 listenerFromBar 会读到自己的监听，
        // 代点时递归调回来。
        final View.OnClickListener ownMenuListener =
                useFrameworkProxy ? null : listenerFromBar(bar);

        ViewGroup wrapper = (ViewGroup) frameLayoutCls
                .getConstructor(Context.class).newInstance(ctx);
        ViewGroup button = (ViewGroup) stateButtonCls
                .getConstructor(Context.class).newInstance(ctx);

        int[] padding = resolveButtonPadding(bar, ctx);
        button.setPadding(padding[0], padding[1], padding[0], padding[1]);

        View icon = new View(ctx);
        // 同一个 × 矢量，整体染红 —— 比自绘更贴近原图标（外框那道浅描边也会一起变红）
        Drawable cross = loadCloseIcon(ctx, closeIconId);
        cross.setTint(FORCE_CLOSE_RED);
        icon.setBackground(cross);
        button.addView(icon, new FrameLayout.LayoutParams(-2, -2));
        button.setContentDescription("彻底关闭");
        button.setFocusable(true);
        button.setClickable(true);
        // 按下时的圆角遮罩 —— 它就是按钮自己的 background（状态列表 drawable，框架的
        // createAndAddCaptionButton 里对每个按钮都设了）。不设的话 MiuiCaptionStateButton
        // 虽然照样 setPressed()，但没有任何东西去画那个状态，点下去就没反馈。
        if (pressSelectorId != 0) {
            button.setBackgroundResource(pressSelectorId);
        }
        View.OnClickListener ownClick = v -> {
            // 1) 先触发小米自己的「关闭」：关闭动画、任务移除、最近任务刷新全都跟正常关闭一致
            //    （这也是不自己画动画的原因）。绝对不能先调 closeHandleMenu ——
            //    MiuiCaptionClickListener.onClick 开头会判 isHandleMenuActive()，菜单先关了它
            //    就直接 return，什么都发生不了。
            //
            //    这里**直接调框架那个监听**，而不是 findViewById 代点「关闭」按钮：
            //    按钮可能被「按钮开关」隐藏掉，代点会落空（表现就是没有关闭动画）。
            //    onClick 只按 view id 分派关闭动作，所以拿个哑 View 冒个 id 就够了。
            View.OnClickListener listener = frameworkMenuClickListener;
            if (useFrameworkProxy && listener != null && closeButtonId != 0) {
                if (closeProxyView == null) {
                    View proxy = new View(ctx);
                    proxy.setId(closeButtonId);
                    closeProxyView = proxy;
                }
                listener.onClick(closeProxyView);
            } else if (ownMenuListener != null && closeButtonId != 0) {
                // 多分屏顶栏菜单：用它自己的框架监听配一个带 state_close id 的哑 View 代点
                // （onClick 只按 view id 分派关闭动作，和框架按钮点下去完全同一条路径）。
                View proxy = new View(ctx);
                proxy.setId(closeButtonId);
                ownMenuListener.onClick(proxy);
            } else {
                // 连监听都拿不到才退化成 findViewByI 代点
                View frameworkClose = bar.findViewById(closeButtonId);
                if (frameworkClose != null) {
                    frameworkClose.performClick();
                }
            }
            // 2) 等关闭转场播完，再彻底收掉：摘任务（卡片消失）+ 杀进程（后台不留）。
            //    杀早了就是「动画闪一下」。拿不到任务信息的场合只做第 1 步。
            if (taskId >= 0 && pkg != null) {
                v.postDelayed(() -> killApp(ctx, pkg, userId, taskId), FORCE_KILL_DELAY_MS);
            }
        };
        button.setOnClickListener(ownClick);
        // 框架 addMouseHoverEffect 会重设按钮条里所有子 View 的监听，
        // 把自己的先存起来，proceed 之后由 restoreOwnListeners 按 tag 恢复。
        button.setTag(LISTENER_TAG_KEY, ownClick);

        wrapper.setId(FORCE_CLOSE_BUTTON_ID);
        wrapper.setPadding(1, 1, 1, 1);
        wrapper.addView(button, new FrameLayout.LayoutParams(-1, -1));

        // 复用相邻按钮的布局参数，尺寸/间距与框架按钮完全一致
        ViewGroup.MarginLayoutParams lp = oneSlotParams(bar);
        if (lp == null) {
            return;
        }
        bar.addView(wrapper, -1, lp);
        // 与框架按钮对齐：magic view 三件套 + 菜单位置（长按别被外层当成「点外面」）
        applyMagicFlags(wrapper);
        applyMagicFlags(button);
        applyMagicFlags(icon);
        Object pos = readField(host, "mHandleMenuPosition");
        if (pos instanceof Point) {
            callIntSetter(wrapper, "setMiuiFreeformHandleMenuPositionX", ((Point) pos).x);
            callIntSetter(wrapper, "setMiuiFreeformHandleMenuPositionY", ((Point) pos).y);
        }
    }

    /**
     * 补一个「新窗口」按钮。
     *
     * <p>框架只在「支持多实例」时才加它（{@code MiuiCaptionContainerView.init} 的 {@code z2}）。
     * 补的时候把 **id 设成 {@code R.id.state_newwindow}、点击监听直接用框架自己那个
     * {@code MiuiCaptionClickListener}** —— 于是点它的行为和原生「新窗口」完全一致
     * （窗口数上限的 toast、埋点都在框架那边）。
     */
    private void addNewWindowButton(ViewGroup bar, Object frameworkListener) throws Exception {
        if (newWindowId == 0 || newWindowIconId == 0) {
            return;
        }
        if (bar.findViewById(newWindowId) != null) {
            return; // 框架已经加了
        }
        Context ctx = bar.getContext();
        ViewGroup wrapper = (ViewGroup) frameLayoutCls
                .getConstructor(Context.class).newInstance(ctx);
        ViewGroup button = (ViewGroup) stateButtonCls
                .getConstructor(Context.class).newInstance(ctx);

        int[] padding = resolveButtonPadding(bar, ctx);
        button.setPadding(padding[0], padding[1], padding[0], padding[1]);

        View icon = new View(ctx);
        icon.setBackground(ctx.getDrawable(newWindowIconId));
        button.addView(icon, new FrameLayout.LayoutParams(-2, -2));
        button.setId(newWindowId);
        button.setContentDescription("新窗口");
        button.setFocusable(true);
        button.setClickable(true);
        if (pressSelectorId != 0) {
            button.setBackgroundResource(pressSelectorId);
        }
        if (frameworkListener instanceof View.OnClickListener) {
            View.OnClickListener own = (View.OnClickListener) frameworkListener;
            button.setOnClickListener(own);
            button.setTag(LISTENER_TAG_KEY, own);
        }

        wrapper.setPadding(1, 1, 1, 1);
        wrapper.addView(button, new FrameLayout.LayoutParams(-1, -1));

        ViewGroup.MarginLayoutParams lp = oneSlotParams(bar);
        if (lp != null) {
            bar.addView(wrapper, -1, lp);
            applyMagicFlags(wrapper);
            applyMagicFlags(button);
            applyMagicFlags(icon);
        }
    }

    /** 从已有按钮上读内边距，读不到就按 dp 兜底。 */
    private static int[] resolveButtonPadding(ViewGroup bar, Context ctx) {
        try {
            View first = bar.getChildAt(0);
            if (first instanceof ViewGroup) {
                View inner = ((ViewGroup) first).getChildAt(0);
                if (inner != null && inner.getPaddingLeft() >= 0 && inner.getPaddingTop() >= 0) {
                    return new int[]{inner.getPaddingLeft(), inner.getPaddingTop()};
                }
            }
        } catch (Throwable ignored) {
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        return new int[]{(int) (FALLBACK_PAD_LR_DP * d), (int) (FALLBACK_PAD_TB_DP * d)};
    }

    /** 抄一个同级子 View 的 MarginLayoutParams（宽高 + 四边距）。 */
    private static ViewGroup.MarginLayoutParams oneSlotParams(ViewGroup bar) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            ViewGroup.LayoutParams lp = bar.getChildAt(i).getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams src = (ViewGroup.MarginLayoutParams) lp;
                if (src.width > 0) {
                    ViewGroup.MarginLayoutParams copy =
                            new ViewGroup.MarginLayoutParams(src.width, src.height);
                    copy.setMargins(src.leftMargin, src.topMargin, src.rightMargin, src.bottomMargin);
                    return copy;
                }
            }
        }
        return null;
    }

    // --------------------------------------------- 2b) OS4 多分屏顶栏小窗菜单

    /**
     * OS4 的「多分屏」模式下，窗口顶栏点开的是**另一套**视图：
     * {@code MultipleSplitTopMenuController.createTopMenu} →
     * {@code MultipleSplitTopMenuContainer.init}（全屏 / 投屏 / 多分屏 / 小窗 / 新窗口 / 关闭）
     * → {@code addMouseHoverEffect(ViewGroup)}。
     *
     * <p>好消息是它复用同一套 {@code R.id.state_*}，所以按钮显隐 / 排序直接共用
     * [idToKey]；坏消息是这里没有 {@code MiuiDecorationDot}，也就没有
     * {@code frameworkMenuClickListener} 与下滑关闭逻辑 —— 红色按钮改成代点它自己的
     * 关闭按钮（{@code addForceCloseButton} 的 useFrameworkProxy=false 分支）。
     */
    private void installMultipleSplitMenuHook(ClassLoader cl) {
        Method m = findMethod(cl, CLS_MULTI_SPLIT_CONTROLLER, "addMouseHoverEffect", 1);
        if (m == null) {
            L.INSTANCE.w("event=caption_hook_skipped reason=multi_split_menu_method_not_found");
            return;
        }
        try {
            m.setAccessible(true);
            module.hook(m)
                    .setId("caption_multiple_split_menu")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        ViewGroup bar = chain.getArg(0) instanceof ViewGroup
                                ? (ViewGroup) chain.getArg(0) : null;
                        Object controller = chain.getThisObject();
                        boolean active = HookPrefs.captionEnabled() && bar != null;
                        // ① 先追加，让框架 addMouseHoverEffect 给新按钮挂私有 hover 属性；
                        //    listenerFromBar 必须在追加之前取，否则会读到我们自己的监听。
                        try {
                            if (active) {
                                bar.setTag(BAR_TAG_KEY, BAR_TAG_VALUE);
                                View.OnClickListener frameworkListener = listenerFromBar(bar);
                                if (HookPrefs.captionForceClose()) {
                                    addForceCloseButton(bar, controller, false);
                                }
                                if (HookPrefs.captionAlwaysNewWindow()) {
                                    addNewWindowButton(bar, frameworkListener);
                                }
                            }
                        } catch (Throwable t) {
                            L.INSTANCE.w("event=caption_multi_split_menu_failed msg=" + t.getMessage());
                        }
                        Object result = chain.proceed();
                        try {
                            if (active) {
                                restoreOwnListeners(bar);
                                applyButtonConfig(bar, multiSplitIdToKey, controller);
                                ensureCloseMinus(bar);
                                dumpBarAfterLayout(bar);
                            }
                        } catch (Throwable t) {
                            L.INSTANCE.w("event=caption_multi_split_post_failed msg=" + t.getMessage());
                        }
                        return result;
                    });
        } catch (Throwable t) {
            L.INSTANCE.e("event=hook_failed target=caption_multiple_split_menu", t);
        }
    }

    /** 从菜单里已有的框架按钮上把 OnClickListener 取出来（补「新窗口」按钮时复用）。 */
    private static View.OnClickListener listenerFromBar(ViewGroup bar) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            for (View v : new View[]{child, firstChildOf(child)}) {
                if (v == null) continue;
                try {
                    Object listener = readField(readField(v, "mListenerInfo"), "mOnClickListener");
                    if (listener instanceof View.OnClickListener) {
                        return (View.OnClickListener) listener;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 恢复模块追加按钮自己的点击监听。
     *
     * <p>为了让新按钮和框架按钮走**完全相同**的触摸配置，把按钮的添加提前到
     * {@code addMouseHoverEffect} 的 {@code chain.proceed()} 之前：框架会自己给它挂
     * {@code setMagicView / setPointerHide / setWrapped / setXOffset / ... / Folme hover}。
     * 代价是框架同时会 {@code setOnClickListener(frameworkMenuClickListener)} 覆盖我们的
     * 监听（{@code MiuiCaptionFrameLayout} 把它转发给第一个子 View），所以 proceed 之后
     * 按 [LISTENER_TAG_KEY] 恢复。
     *
     * <p>早先「proceed 之后追加 + 反射补私有属性」不可靠：hidden API 反射可能被拦，而
     * {@code MiuiCaptionStateButton.onTouchEvent} 根本不调 super，补
     * {@code setOnLongClickListener} / {@code setLongClickable} 不会被触发 —— 表现就是
     * 新按钮长按仍被外层判成菜单外触摸、菜单直接退出（「按不住，只能点一下」）。
     */
    private static void restoreOwnListeners(ViewGroup bar) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            for (View v : new View[]{child, firstChildOf(child), firstChildOf(firstChildOf(child))}) {
                if (v == null) {
                    continue;
                }
                Object tag = v.getTag(LISTENER_TAG_KEY);
                if (tag instanceof View.OnClickListener) {
                    v.setOnClickListener((View.OnClickListener) tag);
                }
            }
        }
    }

    /**
     * 菜单建好之后**再确认一遍**「关闭」按钮的图标是不是 −。
     *
     * <p>只在 {@code setIcon} / {@code createStateButton} 里换一次是不够的：多分屏那套按钮会被
     * 复用，关掉菜单再打开时框架可能不再调 {@code setIcon}，也可能又拿资源把背景重置了一次 ——
     * 两种情况都会让 − 消失（表现就是「关闭后再打开，图标没了」）。这里在建好的按钮条上兜底
     * 补一次，成本只有一次子树遍历。也因为它是幂等的（背景已经是 − 就直接返回），每次建菜单
     * 都跑一遍不会有副作用。
     */
    private void ensureCloseMinus(ViewGroup bar) {
        if (!HookPrefs.captionEnabled() || !HookPrefs.captionCloseAsMinus()) {
            return;
        }
        View close = findCloseButton(bar);
        if (close instanceof ViewGroup) {
            swapIconGroup((ViewGroup) close);
        } else if (close != null) {
            swapIconDrawable(close);
        }
    }

    /** 在按钮条里找出框架的「关闭」按钮（它的 R.id 直接挂在自己身上）。 */
    private View findCloseButton(ViewGroup bar) {
        if (bar == null || closeButtonId == 0) {
            return null;
        }
        try {
            return bar.findViewById(closeButtonId);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把多分屏菜单的视图树（布局完成后）落盘一份。
     *
     * <p>「图标不显示」这类问题分两种：要么 drawable 没了（背景为 null / 尺寸为 0），
     * 要么 View 被挤出窗口（宽高为 0）。日志本机收不到，只能靠这份诊断区分。
     */
    private static void dumpBarAfterLayout(final ViewGroup bar) {
        try {
            bar.post(() -> {
                try {
                    StringBuilder sb = new StringBuilder();
                    sb.append("bar=").append(bar.getClass().getSimpleName())
                            .append(" children=").append(bar.getChildCount())
                            .append(" w=").append(bar.getWidth())
                            .append(" h=").append(bar.getHeight());
                    for (int i = 0; i < bar.getChildCount(); i++) {
                        View child = bar.getChildAt(i);
                        sb.append("\n[").append(i).append("] ")
                                .append(child.getClass().getSimpleName())
                                .append(" id=0x").append(Integer.toHexString(child.getId()))
                                .append(" vis=").append(child.getVisibility())
                                .append(" w=").append(child.getWidth())
                                .append(" h=").append(child.getHeight())
                                .append(" x=").append(child.getLeft())
                                .append(" y=").append(child.getTop());
                        if (child instanceof ViewGroup) {
                            ViewGroup group = (ViewGroup) child;
                            sb.append(" kids=").append(group.getChildCount());
                            for (int j = 0; j < group.getChildCount(); j++) {
                                View leaf = group.getChildAt(j);
                                Drawable bg = leaf.getBackground();
                                sb.append("\n    leaf").append(j)
                                        .append(" id=0x").append(Integer.toHexString(leaf.getId()))
                                        .append(" bg=").append(bg == null
                                                ? "null" : bg.getClass().getSimpleName())
                                        .append(" iw=").append(bg == null
                                                ? -1 : bg.getIntrinsicWidth())
                                        .append(" ih=").append(bg == null
                                                ? -1 : bg.getIntrinsicHeight())
                                        .append(" w=").append(leaf.getWidth())
                                        .append(" h=").append(leaf.getHeight())
                                        .append(" vis=").append(leaf.getVisibility());
                                // 按钮里真正画图标的是再下一层（MultipleSplitTopMenuButton /
                                // MiuiCaptionStateButton 都是 FrameLayout，setIcon 往里面 addView）。
                                // 「图标消失」要看这层的背景与尺寸。
                                if (leaf instanceof ViewGroup) {
                                    ViewGroup lvg = (ViewGroup) leaf;
                                    for (int k = 0; k < lvg.getChildCount(); k++) {
                                        View glyph = lvg.getChildAt(k);
                                        Drawable gbg = glyph.getBackground();
                                        sb.append("\n      glyph").append(k)
                                                .append(" bg=").append(gbg == null
                                                        ? "null" : gbg.getClass().getSimpleName())
                                                .append(" iw=").append(gbg == null
                                                        ? -1 : gbg.getIntrinsicWidth())
                                                .append(" ih=").append(gbg == null
                                                        ? -1 : gbg.getIntrinsicHeight())
                                                .append(" w=").append(glyph.getWidth())
                                                .append(" h=").append(glyph.getHeight());
                                    }
                                }
                            }
                        }
                    }
                    Diag.INSTANCE.write("caption_ms_menu_layout", sb.toString());
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }


    // --------------------------------------------- 3) 菜单窗口宽度跟随按钮数
    //
    // createHandleMenu 是按「按钮宽×个数 + 边距×(个数+1)」**算好宽高才调 addWindow** 的，
    // 而我们在它之后改了按钮集合（追加 / 隐藏），所以这里按 bar 的**实际子 View**重算宽度，
    // x 左移半个差值保持居中。
    private void installWidenMenuHook(ClassLoader cl) {
        Method addWindow = findMethod(cl, CLS_DOT, "addWindow", 8);
        if (addWindow == null) {
            L.INSTANCE.w("event=caption_hook_skipped reason=addWindow_not_found");
            return;
        }
        try {
            addWindow.setAccessible(true);
            module.hook(addWindow)
                    .setId("caption_add_window")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            if (HookPrefs.captionEnabled()
                                    && chain.getArg(0) instanceof ViewGroup
                                    && chain.getArg(1) instanceof Integer
                                    && chain.getArg(3) instanceof Integer) {
                                ViewGroup container = (ViewGroup) chain.getArg(0);
                                ViewGroup bar = findTaggedBar(container);
                                if (bar != null && bar.getChildCount() > 0) {
                                    int originalX = (Integer) chain.getArg(1);
                                    int originalY = (Integer) chain.getArg(2);
                                    int originalWidth = (Integer) chain.getArg(3);
                                    // 宽度按**实际按钮数**算，内容才刚好撑满窗口
                                    int width = barContentWidth(bar);
                                    // **定位沿用 MIUI 的**：它给的 x 已经是「以三个控制点为中心」
                                    // 算出来的（常规 rect.centerX()-w/2；小窗
                                    // rect.left + rect.width()*scale/2 - w/2），这里不另挑基准、
                                    // 也不强制居中 —— 只按宽度差值挪半个差值，让加/删按钮后中心不变；
                                    // 宽度没变时 newX == originalX，等于原样不动。
                                    int newX = originalX - (width - originalWidth) / 2;
                                    if (width > 0 && (width != originalWidth || newX != originalX)) {
                                        // 换过宽度的窗口，MIUI 记下来的菜单位置还是旧的：
                                        // mHandleMenuPosition 和每个按钮的
                                        // setMiuiFreeformHandleMenuPositionX/Y 都停在 originalX 上。
                                        // 于是 isTouchedInHandleMenu()（外面用来判断「是不是点在菜单里」）
                                        // 和 magic view 的长按判定都用错坐标系 —— 缩窄时窗口右移半个差值，
                                        // 最右边的按钮就会被判成「菜单外」，按住不放直接把菜单关掉。
                                        // 位置一定要跟着窗口一起改。
                                        syncMenuGeometry(chain.getThisObject(), bar, newX, originalY);
                                        Object[] args = chain.getArgs().toArray();
                                        args[1] = newX;
                                        args[3] = width;
                                        // 枢轴同步改成「控制点相对**新**窗口左边缘的位置」（= width/2），
                                        // 出现动画才会从正中间长出来。
                                        if (args[5] instanceof Integer) {
                                            args[5] = ((Integer) args[5]) - (newX - originalX);
                                        }
                                        return chain.proceed(args);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            L.INSTANCE.w("event=caption_widen_failed msg=" + t.getMessage());
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            L.INSTANCE.e("event=hook_failed target=caption_add_window", t);
        }
    }

    /**
     * OS4 多分屏顶栏菜单的窗口宽高是 {@code createTopMenu} 的局部变量，传给
     * {@code addWindow(View, int width, int height)}，在 {@code addMouseHoverEffect} 之前
     * 就按框架按钮数算好了。我们在 hover 之后追加按钮，不重算就会让新按钮溢出窗口：
     * 触点落到 {@code isTouchedInHandleMenu} 的范围外，表现就是「长按直接把菜单关掉」。
     *
     * <p>这里按 bar 的实际子 View 重算宽度，并同步 {@code mTopMenuBounds.right}
     * （出现动画与触摸判定都读它）；left/top 不动，保持框架自己的定位。
     */
    private void installMultipleSplitWidenHook(ClassLoader cl) {
        Method addWindow = findMethod(cl, CLS_MULTI_SPLIT_CONTROLLER, "addWindow", 3);
        if (addWindow == null) {
            L.INSTANCE.w("event=caption_hook_skipped reason=multi_split_add_window_not_found");
            return;
        }
        try {
            addWindow.setAccessible(true);
            module.hook(addWindow)
                    .setId("caption_multi_split_add_window")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        try {
                            if (HookPrefs.captionEnabled()
                                    && chain.getArg(0) instanceof View
                                    && chain.getArg(1) instanceof Integer) {
                                ViewGroup bar = findTaggedBar((View) chain.getArg(0));
                                if (bar != null && bar.getChildCount() > 0) {
                                    int originalWidth = (Integer) chain.getArg(1);
                                    int width = barContentWidth(bar);
                                    if (width > 0 && width != originalWidth) {
                                        // 窗口 surface 的左上角就是 mTopMenuBounds.left/top，宽度只用
                                        // 来 setWindowCrop。所以**只改 right 会让菜单向右多长一截**
                                        // （左侧不动），看上去偏右半个差值。
                                        // 正确做法：left 左移半个差值，right = left + 新宽度 ——
                                        // 窗口中心仍是 calculateTopMenuPos 算出来的 stage 中心。
                                        Object bounds = readField(chain.getThisObject(), "mTopMenuBounds");
                                        if (bounds instanceof Rect) {
                                            Rect rect = (Rect) bounds;
                                            int oldWidth = rect.width();
                                            if (oldWidth != width) {
                                                int dx = (width - oldWidth) / 2;
                                                rect.left -= dx;
                                                rect.right = rect.left + width;
                                            }
                                        }
                                        Diag.INSTANCE.write("caption_ms_widen",
                                                "original=" + originalWidth + " new=" + width
                                                        + " children=" + bar.getChildCount()
                                                        + " bounds=" + bounds);
                                        Object[] args = chain.getArgs().toArray();
                                        args[1] = width;
                                        return chain.proceed(args);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            L.INSTANCE.w("event=caption_multi_split_widen_failed msg=" + t.getMessage());
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            L.INSTANCE.e("event=hook_failed target=caption_multi_split_add_window", t);
        }
    }

    /**
     * 「隐藏三点控制器」：控制点条就是 {@code MiuiDecorationDotView.onDraw} 里画的三个圆，
     * 不画就看不见了。surface 本身还在，所以那块区域**仍然点得开**控制菜单。
     */
    private void installHideDotsHook(ClassLoader cl) {
        Method onDraw = findMethod(cl, CLS_DOT_VIEW, "onDraw", 1);
        if (onDraw == null) {
            L.INSTANCE.w("event=caption_hook_skipped reason=dot_onDraw_not_found");
            return;
        }
        try {
            onDraw.setAccessible(true);
            module.hook(onDraw)
                    .setId("caption_dot_on_draw")
                    .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                    .intercept(chain -> {
                        if (HookPrefs.captionEnabled() && HookPrefs.captionHideDots()) {
                            return null;  // onDraw 是 void，什么都不画
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            L.INSTANCE.w("event=hook_failed target=caption_dot_on_draw msg=" + t.getMessage());
        }
    }


    /**
     * 把「菜单窗口在屏幕上的位置」同步到 MIUI 记着的所有地方。
     *
     * <p>{@code createHandleMenu} 先用旧按钮数算出 {@code mHandleMenuPosition}，再用它给
     * bar 里每个按钮设 {@code setMiuiFreeformHandleMenuPositionX/Y}（magic view 自己的坐标系），
     * 最后才调 {@code addWindow}。我们在 addWindow 里改了 x，
     * 这两处就都留在旧位置上：
     *
     * <ul>
     *   <li>{@code isTouchedInHandleMenu(MotionEvent)} = {@code pointInView(菜单根视图,
     *       event.x - mHandleMenuPosition.x, ...)}，位置偏了就会把真实的按钮按点算到视图外面，
     *       外部（SystemUI 的手势/触摸判定）就会当成「点在菜单外」→ 菜单被关掉；</li>
     *   <li>magic view 的长按 / hover 也用这组坐标，偏了之后长按行为跟框架按钮不一致。</li>
     * </ul>
     *
     * <p>缩窄窗口时（用户隐藏了若干按钮）窗口右移半个差值，最右边的按钮正好被算到右边界之外 ——
     * 这就是「新加的关闭按钮一按住菜单就消失，而左边框架按钮正常」的由来。
     */
    private static void syncMenuGeometry(Object dot, ViewGroup bar, int x, int y) {
        Object pos = readField(dot, "mHandleMenuPosition");
        if (pos instanceof Point) {
            ((Point) pos).x = x;
            ((Point) pos).y = y;
        }
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            callIntSetter(child, "setMiuiFreeformHandleMenuPositionX", x);
            callIntSetter(child, "setMiuiFreeformHandleMenuPositionY", y);
        }
    }

    /** 反射调 View 上的一个小米私有 int setter（不存在就跳过，不影响主流程）。 */
    private static void callIntSetter(View view, String name, int value) {
        try {
            View.class.getMethod(name, int.class).invoke(view, value);
        } catch (Throwable ignored) {
        }
    }

    /** 反射调 View 上的一个小米私有 boolean setter。 */
    private static void callBooleanSetter(View view, String name) {
        try {
            View.class.getMethod(name, boolean.class).invoke(view, true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 给模块自己加的按钮补齐 magic view 属性。
     *
     * <p>正常情况下 {@code addMouseHoverEffect} 会遍历 bar 把这三个属性挂到每个子 View 上
     * （长按不被当成「拖窗口 / 点外面」靠它）。但那条遍历发生在 {@code chain.proceed()} 里，
     * 一旦我们的按钮是在 proceed 之后才补进去的（或父容器换了），属性就漏了。这里幂等地再设一遍 ——
     * 已经是 true 的重复设置没有任何副作用。
     */
    private static void applyMagicFlags(View view) {
        if (view == null) {
            return;
        }
        callBooleanSetter(view, "setMagicView");
        callBooleanSetter(view, "setPointerHide");
        callBooleanSetter(view, "setWrapped");
    }

    /** 找回打过标记的按钮条（在容器视图的子树里）。 */
    private static ViewGroup findTaggedBar(View root) {
        if (root instanceof ViewGroup && root.getTag(BAR_TAG_KEY) == BAR_TAG_VALUE) {
            return (ViewGroup) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                ViewGroup found = findTaggedBar(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 按钮条实际内容宽 = padding + 每个子 View 的 (宽 + 左右边距)。 */
    private static int barContentWidth(ViewGroup bar) {
        int width = bar.getPaddingLeft() + bar.getPaddingRight();
        for (int i = 0; i < bar.getChildCount(); i++) {
            ViewGroup.LayoutParams lp = bar.getChildAt(i).getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                if (mlp.width > 0) {
                    width += mlp.width + mlp.leftMargin + mlp.rightMargin;
                }
            }
        }
        return width;
    }

    // ------------------------------------------------------------- 彻底关闭应用

    /**
     * 彻底收掉一个应用。三步各管一件事（不是同一个目的的备选）：
     *
     * <ol>
     *   <li>{@code IActivityTaskManager.removeTask(taskId)} —— 把任务摘掉，
     *       最近任务 / 底栏的**卡片才会消失**。这是 Android 自己的移除任务 API，
     *       MIUI 的「退出应用」手势（QuitFocusedAppKeyGestureHandler）用的就是它。
     *       实测只调 forceStopPackage 的话，HyperOS 上卡片会留着。</li>
     *   <li>{@code IActivityManager.forceStopPackage(pkg, userId)} —— 杀进程，后台不留。</li>
     *   <li>{@code ActivityManager.killBackgroundProcesses(pkg)} —— 公开 API 的 kill，
     *       此时任务已摘掉、进程已转后台，收掉缓存进程。</li>
     * </ol>
     *
     * <p>{@code IActivityTaskManager} / {@code IActivityManager} 都是 @hide
     * （compileSdk 里没有），只能反射；{@code getService()} 本身 WMShell 就在直接调用。
     */
    private static void killApp(Context ctx, String pkg, int userId, int taskId) {
        // 1) 摘任务 —— 卡片从最近任务里消失
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService").invoke(null);
            Class.forName("android.app.IActivityTaskManager")
                    .getMethod("removeTask", int.class)
                    .invoke(atm, taskId);
            L.INSTANCE.i("event=caption_remove_task task=" + taskId);
        } catch (Throwable t) {
            L.INSTANCE.w("event=caption_remove_task_failed task=" + taskId + " msg=" + t);
        }
        // 2) 杀进程 —— Android 的强制停止
        try {
            Object am = Class.forName("android.app.ActivityManager")
                    .getMethod("getService").invoke(null);
            Class.forName("android.app.IActivityManager")
                    .getMethod("forceStopPackage", String.class, int.class)
                    .invoke(am, pkg, userId);
            L.INSTANCE.i("event=caption_force_close pkg=" + pkg + " task=" + taskId);
        } catch (Throwable t) {
            L.INSTANCE.w("event=caption_force_close_failed pkg=" + pkg + " msg=" + t);
        }
        // 3) 公开 API 再收一次缓存进程
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(pkg);
            }
        } catch (Throwable ignored) {
        }
    }

    /** {@code TaskInfo.userId} 是 @hide，反射读；拿不到就退回本进程所在用户。 */
    private static int userIdOf(ActivityManager.RunningTaskInfo info) {
        Object v = readField(info, "userId");
        if (v instanceof Integer) {
            return (Integer) v;
        }
        try {
            // UserHandle.myUserId() / getIdentifier() 都是 @hide；
            // 公开 API 里最省事的换算就是 uid / 100000（uid = userId*100000 + appId）
            return android.os.Process.myUid() / 100000;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 工具

    private static ActivityManager.RunningTaskInfo resolveTaskInfo(Object host) {
        // 普通小窗菜单：MiuiDecorationDot → mMiuiDecorationController.mRunningTaskInfo
        Object info = readField(readField(host, "mMiuiDecorationController"), "mRunningTaskInfo");
        if (info instanceof ActivityManager.RunningTaskInfo) {
            return (ActivityManager.RunningTaskInfo) info;
        }
        // OS4 多分屏顶栏菜单：controller 没有这个字段链，退而扫它自己/
        // 关联对象里的 RunningTaskInfo。找不到就返回 null，按钮降级为「代点关闭」。
        info = findTaskInfo(host, 0);
        return info instanceof ActivityManager.RunningTaskInfo
                ? (ActivityManager.RunningTaskInfo) info : null;
    }

    /** 沿对象字段做浅层搜索（最多 3 层），只在菜单弹出时跑一次。 */
    private static Object findTaskInfo(Object obj, int depth) {
        if (obj == null || depth > 3) return null;
        if (obj instanceof ActivityManager.RunningTaskInfo) return obj;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v instanceof ActivityManager.RunningTaskInfo) return v;
                } catch (Throwable ignored) {
                }
            }
        }
        for (String name : new String[]{
                "mMultipleSplitUIController", "mSplitLayout", "mParentContainerCallbacks"}) {
            Object found = findTaskInfo(readField(obj, name), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static String packageOf(ActivityManager.RunningTaskInfo info) {
        ComponentName cn = info.baseActivity != null ? info.baseActivity : info.topActivity;
        return cn == null ? null : cn.getPackageName();
    }

    private static Drawable loadCloseIcon(Context ctx, int id) {
        return ctx.getDrawable(id);
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** 读一个 int 字段（沿父类链找）；读不到返回 0。 */
    private static int readIntField(Object target, String name) {
        Object value = readField(target, name);
        return value instanceof Integer ? (Integer) value : 0;
    }

    private static View firstChildOf(Object view) {
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        return group.getChildCount() == 0 ? null : group.getChildAt(0);
    }

    private static boolean isNight(View view) {
        return (view.getContext().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static boolean isArg(XposedInterface.Chain chain, int index, int expected) {
        Object arg = chain.getArg(index);
        return (arg instanceof Integer) && ((Integer) arg) == expected;
    }

    /** 按「方法名 + 参数个数」定位，避免参数类型漂移时整体失效。 */
    private static Method findMethod(ClassLoader cl, String className, String name, int paramCount) {
        try {
            for (Method m : cl.loadClass(className).getDeclaredMethods()) {
                if (name.equals(m.getName()) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 反射读 R 里的资源 id（{@code R$drawable} / {@code R$id}）。 */
    private static int resolveResId(ClassLoader cl, String rClassName, String name) {
        try {
            Class<?> r = cl.loadClass(rClassName);
            Field f = r.getDeclaredField(name);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 复刻 {@code res/drawable/caption_close.xml} 的「圆角外框」+ 中间一条横线（−）。
     *
     * <p>原始矢量是 10dp×10dp / viewport 20×20，两条 path：
     * <ol>
     *   <li>外框：圆角矩形 0.9..19.1，strokeWidth 1.8，strokeAlpha 0.3；</li>
     *   <li>字形：× ，fillColor 与外框同色（日间 #ff191919，夜间 #ffffffff）。</li>
     * </ol>
     * 这里保留外框，把字形换成横线；颜色<b>从原 drawable 里采样</b>
     * （取 alpha 最大的像素，也就是笔画的实心处），所以日/夜、以及小米以后改配色都能自动跟上，
     * 只有采样失败才退回按夜间标志猜。
     *
     * <p>所有几何都按 viewport(20) 归一化后乘真实宽度，因此换分辨率/密度不会走形。
     */
    static final class FrameWithMinusDrawable extends Drawable {

        private static final float FRAME_INSET = 0.9f / 20f;
        private static final float FRAME_STROKE = 1.8f / 20f;
        private static final float FRAME_RADIUS = 3.0f / 20f;
        private static final float FRAME_ALPHA = 0.3f;
        /** 横线半长：原 × 的横向范围是 6.113..13.887（半长 3.887）。 */
        private static final float BAR_HALF_LEN = 3.887f / 20f;
        private static final float BAR_THICKNESS = 1.8f / 20f;
        /** 横线的 alpha 下限：避免采到极淡的字形色后「线在，但看不见」。 */
        private static final int MIN_GLYPH_ALPHA = 0x60;

        private final int intrinsicWidth;
        private final int intrinsicHeight;
        private final int glyphColor;
        private final int frameColor;
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF frameRect = new RectF();
        private final RectF barRect = new RectF();
        private int drawAlpha = 255;

        FrameWithMinusDrawable(Drawable original, int width, int height, boolean night) {
            this.intrinsicWidth = width;
            this.intrinsicHeight = height;
            this.glyphColor = sampleGlyphColor(original, width, height, night);
            this.frameColor = (glyphColor & 0x00FFFFFF) | 0xFF000000;
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setColor(frameColor);
            barPaint.setStyle(Paint.Style.FILL);
            barPaint.setColor(glyphColor);
        }

        /**
         * 从原 drawable 里取「字形色」。
         *
         * <p>不再只采中心 1 个像素 —— 图标尺寸/变体一变，中心点就可能落在 × 两条笔画之间的
         * 空隙上，采到的 alpha 极小（甚至 0～几个单位），画出来的横线等于看不见，表现就是
         * 「图标没了」。这里按缩放后的小图**全量扫一遍取 alpha 最大的像素**：那必然是笔画的
         * 实心处，颜色和粗细都能跟原图标对齐。alpha 再兜一个下限，避免本来就极淡的图标
         * 被我们改得毫无存在感。
         */
        private static int sampleGlyphColor(Drawable original, int w, int h, boolean night) {
            Bitmap bmp = null;
            try {
                float scale = Math.min(1f, 96f / Math.max(w, h));
                int sw = Math.max(1, Math.round(w * scale));
                int sh = Math.max(1, Math.round(h * scale));
                bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                original.setBounds(0, 0, sw, sh);
                original.draw(canvas);
                int[] pixels = new int[sw * sh];
                bmp.getPixels(pixels, 0, sw, 0, 0, sw, sh);
                int best = 0;
                int bestAlpha = 0;
                for (int px : pixels) {
                    int a = px >>> 24;
                    if (a > bestAlpha) {
                        bestAlpha = a;
                        best = px;
                    }
                }
                if (bestAlpha > 0) {
                    int alpha = Math.max(bestAlpha, MIN_GLYPH_ALPHA);
                    return (best & 0x00FFFFFF) | (alpha << 24);
                }
            } catch (Throwable ignored) {
                // 采样失败就走下面的兜底
            } finally {
                if (bmp != null) {
                    bmp.recycle();
                }
            }
            // 兜底：caption_close.xml 两个变体的字形色
            return night ? 0xFFFFFFFF : 0xFF191919;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            float w = bounds.width();
            float h = bounds.height();
            float inset = FRAME_INSET * w;
            frameRect.set(inset, inset, w - inset, h - inset);
            framePaint.setStrokeWidth(FRAME_STROKE * w);

            float cx = w / 2f;
            float cy = h / 2f;
            float half = BAR_HALF_LEN * w;
            float halfThickness = BAR_THICKNESS * w / 2f;
            barRect.set(cx - half, cy - halfThickness, cx + half, cy + halfThickness);
        }

        @Override
        public void draw(Canvas canvas) {
            float scale = drawAlpha / 255f;
            framePaint.setAlpha((int) (Color.alpha(frameColor) * FRAME_ALPHA * scale));
            barPaint.setAlpha((int) (Color.alpha(glyphColor) * scale));
            float r = FRAME_RADIUS * getBounds().width();
            canvas.drawRoundRect(frameRect, r, r, framePaint);

            float cap = barRect.height() / 2f;
            canvas.drawRoundRect(barRect, cap, cap, barPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            drawAlpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            framePaint.setColorFilter(colorFilter);
            barPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        /** API 35 起废弃，但 {@code Drawable} 里仍是抽象方法，必须覆写。 */
        @SuppressWarnings("deprecation")
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return intrinsicWidth;
        }

        @Override
        public int getIntrinsicHeight() {
            return intrinsicHeight;
        }
    }
}
