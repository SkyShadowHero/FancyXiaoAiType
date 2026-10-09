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
    };

    /** 给按钮条打的标记，{@code addWindow} 靠它把那条 bar 找回来算宽度。 */
    private static final int BAR_TAG_KEY = 0x7E0F0002;
    private static final Object BAR_TAG_VALUE = new Object();

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

    public CaptionHooks(XposedInterface module) {
        this.module = module;
    }

    /** 由 {@code XposedEntry} 在 {@code com.android.systemui} 进程里调用。 */
    public void install(ClassLoader classLoader) {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        try {
            closeIconId = resolveResId(classLoader, CLS_R_DRAWABLE, CLOSE_ICON_NAME);
            if (closeIconId == 0) {
                L.INSTANCE.w("event=caption_hook_skipped reason=drawable_not_resolved name="
                        + CLOSE_ICON_NAME);
                return;
            }
            frameLayoutCls = classLoader.loadClass(CLS_FRAME_LAYOUT);
            stateButtonCls = classLoader.loadClass(CLS_STATE_BUTTON);
            newWindowIconId = resolveResId(classLoader, CLS_R_DRAWABLE, NEW_WINDOW_ICON_NAME);
            pressSelectorId = resolveResId(classLoader, CLS_R_DRAWABLE, PRESS_SELECTOR_NAME);

            // 框架按钮 id → key 的对照表（认按钮 / 隐藏 / 排序都要用）
            final Map<Integer, String> idToKey = new HashMap<>();
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

            installCloseIconHook(classLoader, closeIconId);
            installMenuButtonsHook(classLoader, idToKey);
            installWidenMenuHook(classLoader);
            installHideDotsHook(classLoader);

            L.INSTANCE.i("event=caption_hooks_installed closeIcon=0x"
                    + Integer.toHexString(closeIconId)
                    + " closeId=0x" + Integer.toHexString(closeButtonId)
                    + " newWindowId=0x" + Integer.toHexString(newWindowId));
        } catch (Throwable t) {
            L.INSTANCE.e("event=hook_failed target=caption_container", t);
        }
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
        View icon = firstChildOf(button);
        if (icon == null) {
            return;
        }
        Drawable original = icon.getBackground();
        int w = original == null ? 0 : original.getIntrinsicWidth();
        int h = original == null ? 0 : original.getIntrinsicHeight();
        if (original == null || w <= 0 || h <= 0) {
            return;
        }
        icon.setBackground(new FrameWithMinusDrawable(original, w, h, isNight(icon)));
        L.INSTANCE.i("event=caption_icon_swapped from=" + w + "x" + h);
    }

    // ------------------------------------- 2) 菜单按钮：追加 / 隐藏 / 重排
    //
    // 都挂在 MiuiDecorationDot.addMouseHoverEffect 上 —— 它是框架**统一给按钮条里每个
    // 子 View 挂点击监听**的地方（遍历子 View 调 setOnClickListener）。等它跑完再动：
    // 追加不会被框架的监听覆盖，删/排序也不会破坏已经挂好的监听。
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
                        Object result = chain.proceed();
                        try {
                            if (HookPrefs.captionEnabled() && chain.getArg(0) instanceof ViewGroup) {
                                ViewGroup bar = (ViewGroup) chain.getArg(0);
                                Object dot = chain.getThisObject();
                                // arg2 就是框架那个 MiuiCaptionClickListener，后面几个地方都要用
                                if (chain.getArg(2) instanceof View.OnClickListener) {
                                    frameworkMenuClickListener = (View.OnClickListener) chain.getArg(2);
                                }
                                // 打个标记：addWindow 靠它找回这条 bar 算宽度
                                bar.setTag(BAR_TAG_KEY, BAR_TAG_VALUE);
                                if (HookPrefs.captionForceClose()) {
                                    addForceCloseButton(bar, dot);
                                }
                                if (HookPrefs.captionAlwaysNewWindow()) {
                                    addNewWindowButton(bar, chain.getArg(2));
                                }
                                applyButtonConfig(bar, idToKey, dot);
                            }
                        } catch (Throwable t) {
                            L.INSTANCE.w("event=caption_menu_button_failed msg=" + t.getMessage());
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
    private void addForceCloseButton(ViewGroup bar, Object dot) throws Exception {
        Context ctx = bar.getContext();
        if (bar.findViewById(FORCE_CLOSE_BUTTON_ID) != null) {
            return; // 已经有就不重复加（同一条 bar 只会构造一次）
        }
        ActivityManager.RunningTaskInfo info = resolveTaskInfo(dot);
        if (info == null) {
            return;
        }
        String pkg = packageOf(info);
        if (pkg == null) {
            return;
        }
        final int userId = userIdOf(info);
        final int taskId = info.taskId;

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
        button.setOnClickListener(v -> {
            // 1) 先触发小米自己的「关闭」：关闭动画、任务移除、最近任务刷新全都跟正常关闭一致
            //    （这也是不自己画动画的原因）。绝对不能先调 closeHandleMenu ——
            //    MiuiCaptionClickListener.onClick 开头会判 isHandleMenuActive()，菜单先关了它
            //    就直接 return，什么都发生不了。
            //
            //    这里**直接调框架那个监听**，而不是 findViewById 代点「关闭」按钮：
            //    按钮可能被「按钮开关」隐藏掉，代点会落空（表现就是没有关闭动画）。
            //    onClick 只按 view id 分派关闭动作，所以拿个哑 View 冒个 id 就够了。
            View.OnClickListener listener = frameworkMenuClickListener;
            if (listener != null && closeButtonId != 0) {
                if (closeProxyView == null) {
                    View proxy = new View(ctx);
                    proxy.setId(closeButtonId);
                    closeProxyView = proxy;
                }
                listener.onClick(closeProxyView);
            } else {
                // 拿不到监听（版本签名变了）才退化成代点按钮
                View frameworkClose = bar.findViewById(closeButtonId);
                if (frameworkClose != null) {
                    frameworkClose.performClick();
                }
            }
            // 2) 等关闭转场播完，再彻底收掉：摘任务（卡片消失）+ 杀进程（后台不留）。
            //    杀早了就是「动画闪一下」。
            v.postDelayed(() -> killApp(ctx, pkg, userId, taskId), FORCE_KILL_DELAY_MS);
        });

        wrapper.setId(FORCE_CLOSE_BUTTON_ID);
        wrapper.setPadding(1, 1, 1, 1);
        wrapper.addView(button, new FrameLayout.LayoutParams(-1, -1));

        // 复用相邻按钮的布局参数，尺寸/间距与框架按钮完全一致
        ViewGroup.MarginLayoutParams lp = oneSlotParams(bar);
        if (lp == null) {
            return;
        }
        bar.addView(wrapper, -1, lp);
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
            button.setOnClickListener((View.OnClickListener) frameworkListener);
        }

        wrapper.setPadding(1, 1, 1, 1);
        wrapper.addView(button, new FrameLayout.LayoutParams(-1, -1));

        ViewGroup.MarginLayoutParams lp = oneSlotParams(bar);
        if (lp != null) {
            bar.addView(wrapper, -1, lp);
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

    private static ActivityManager.RunningTaskInfo resolveTaskInfo(Object dot) {
        Object controller = readField(dot, "mMiuiDecorationController");
        Object info = readField(controller, "mRunningTaskInfo");
        return (info instanceof ActivityManager.RunningTaskInfo)
                ? (ActivityManager.RunningTaskInfo) info : null;
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
     * 这里保留外框，把字形换成横线；颜色<b>从原 drawable 中心点采样</b>
     * （× 的交叉点必然是实心的），所以日/夜、以及小米以后改配色都能自动跟上，
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

        /** 把原 drawable 画到 1×1 位图上，取中心像素当字形颜色。 */
        private static int sampleGlyphColor(Drawable original, int w, int h, boolean night) {
            Bitmap bmp = null;
            try {
                bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                canvas.translate(-w / 2f, -h / 2f);
                original.setBounds(0, 0, w, h);
                original.draw(canvas);
                int sampled = bmp.getPixel(0, 0);
                if (Color.alpha(sampled) != 0) {
                    return sampled;
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
