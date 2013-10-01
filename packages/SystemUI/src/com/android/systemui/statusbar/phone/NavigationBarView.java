/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import java.io.File;
import java.net.URISyntaxException;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.provider.Settings.System;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.animation.AccelerateInterpolator;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.DelegateViewHelper;
import com.android.systemui.statusbar.policy.DeadZone;
//import com.android.systemui.statusbar.policy.buttons.BackKeyWithKillButtonView;
//import com.android.systemui.statusbar.policy.buttons.HomeKeyWithTasksButtonView;
//import com.android.systemui.statusbar.policy.buttons.RecentsKey;
//import com.android.systemui.statusbar.policy.buttons.SearchKeyButtonView;
import com.android.systemui.statusbar.policy.buttons.ExtensibleKeyButtonView;

public class NavigationBarView extends LinearLayout implements BaseStatusBar.NavigationBarCallback {
    final static boolean DEBUG = false;
    final static String TAG = "PhoneStatusBar/NavigationBarView";

    final static boolean NAVBAR_ALWAYS_AT_RIGHT = true;

    // slippery nav bar when everything is disabled, e.g. during setup
    final static boolean SLIPPERY_WHEN_DISABLED= true;

    final static boolean ANIMATE_HIDE_TRANSITION = false; // turned off because it introduces unsightly delay when videos goes to full screen
    final static String NAVBAR_EDIT = "android.intent.action.NAVBAR_EDIT";

    private static boolean EDIT_MODE;
    private NavbarEditor mEditBar;
    private NavBarReceiver mNavBarReceiver;
    private OnClickListener mRecentsClickListener;
    private OnTouchListener mRecentsPreloadListener;
    private OnTouchListener mHomeSearchActionListener;

    protected IStatusBarService mBarService;
    final Display mDisplay;
    View mCurrentView = null;
    View[] mRotatedViews = new View[4];

    int mBarSize;
    boolean mVertical;
    boolean mScreenOn;

    boolean mHidden, mLowProfile, mShowMenu;
    int mDisabledFlags = 0;
    int mNavigationIconHints = 0;

    private Drawable mBackIcon, mBackLandIcon, mBackAltIcon, mBackAltLandIcon;
    private Drawable mRecentIcon;
    private Drawable mRecentLandIcon;

    private DelegateViewHelper mDelegateHelper;
    private DeadZone mDeadZone;
    
    final static String ACTION_HOME = "**home**";
    final static String ACTION_BACK = "**back**";
    final static String ACTION_SEARCH = "**search**";
    final static String ACTION_MENU = "**menu**";
    final static String ACTION_POWER = "**power**";
    final static String ACTION_RECENTS = "**recents**";
    final static String ACTION_KILL = "**kill**";
    final static String ACTION_NULL = "**null**";
    
    int mNumberOfButtons = 3;
    
    public String[] mClickActions = new String[5];
    public String[] mLongpressActions = new String[5];
    public String[] mPortraitIcons = new String[5];
    public String[] mLandscapeIcons = new String[5];
    
    public final static int StockButtonsQty = 3;
    public final static String[] StockClickActions = {
    	"**back**","**home**","**recents**","**null**","**null**"
    };
    
    public final static String[] StockLongpress = {
    	"**null**","**null**","**null**","**null**","**null**"
    };

    public final static int SHOW_LEFT_MENU = 1;
    public final static int SHOW_RIGHT_MENU = 0;
    public final static int SHOW_BOTH_MENU = 2;
    public final static int SHOW_DONT = 4;

    public final static int VISIBILITY_SYSTEM = 0;
    public final static int VISIBILITY_SYSTEM_AND_INVIZ = 3;
    public final static int VISIBILITY_NEVER = 1;
    public final static int VISIBILITY_ALWAYS = 2;
    
    public static final int KEY_MENU_RIGHT = 2;
    public static final int KEY_MENU_LEFT = 5;

    // workaround for LayoutTransitions leaving the nav buttons in a weird state (bug 5549288)
    final static boolean WORKAROUND_INVALID_LAYOUT = true;
    final static int MSG_CHECK_INVALID_LAYOUT = 8686;

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_CHECK_INVALID_LAYOUT:
                    final String how = "" + m.obj;
                    final int w = getWidth();
                    final int h = getHeight();
                    final int vw = mCurrentView.getWidth();
                    final int vh = mCurrentView.getHeight();

                    if (h != vh || w != vw) {
                        Slog.w(TAG, String.format(
                            "*** Invalid layout in navigation bar (%s this=%dx%d cur=%dx%d)",
                            how, w, h, vw, vh));
                        if (WORKAROUND_INVALID_LAYOUT) {
                            requestLayout();
                        }
                    }
                    break;
            }
        }
    }

    public void setDelegateView(View view) {
        mDelegateHelper.setDelegateView(view);
    }

    public void setBar(BaseStatusBar phoneStatusBar) {
        mDelegateHelper.setBar(phoneStatusBar);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mDeadZone != null && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            mDeadZone.poke(event);
        }
        if (mDelegateHelper != null) {
            boolean ret = mDelegateHelper.onInterceptTouchEvent(event);
            if (ret) return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mDelegateHelper.onInterceptTouchEvent(event);
    }
    
   public View getSearchButton() {
        return mCurrentView.findViewById(R.id.search);
    }

    public View getRecentsButton() {
        return mCurrentView.findViewById(R.id.recent_apps);
    }

    private H mHandler = new H();

    public static boolean getEditMode() {
        return EDIT_MODE;
    }

    protected void setListener(OnClickListener RecentsClickListener, OnTouchListener RecentsPreloadListener, OnTouchListener HomeSearchActionListener) {
        mRecentsClickListener = RecentsClickListener;
        mRecentsPreloadListener = RecentsPreloadListener;
        mHomeSearchActionListener = HomeSearchActionListener;
    }

    protected void toggleButtonListener(boolean enable) {
        View recentView = mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_RECENT);
        if (recentView != null) {
            recentView.setOnClickListener(mRecentsClickListener);
            recentView.setOnTouchListener(mRecentsPreloadListener);
        }
        View homeView = mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_HOME);
        if (homeView != null) {
            homeView.setOnTouchListener(enable ? mHomeSearchActionListener : null);
        }
    }

    private void setButtonWithTagVisibility(String string, int visibility) {
        View findView = mCurrentView.findViewWithTag(string);
        if (findView != null) {
            findView.setVisibility(visibility);
        }
    }

    // for when home is disabled, but search isn't
    public View getSearchLight() {
        return mCurrentView.findViewById(R.id.search_light);
    }

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mHidden = false;

        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        final Resources res = mContext.getResources();
        mBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);
        mVertical = false;
        mShowMenu = false;
        mDelegateHelper = new DelegateViewHelper(this);
        updateResources();

        mNavBarReceiver = new NavBarReceiver();
        mContext.registerReceiver(mNavBarReceiver, new IntentFilter(NAVBAR_EDIT));
    }

    FrameLayout rot0;
    FrameLayout rot90;

    private void makeBar() {

        ((LinearLayout) rot0.findViewById(R.id.nav_buttons)).removeAllViews();
        ((LinearLayout) rot0.findViewById(R.id.lights_out)).removeAllViews();
        ((LinearLayout) rot90.findViewById(R.id.nav_buttons)).removeAllViews();
        ((LinearLayout) rot90.findViewById(R.id.lights_out)).removeAllViews();

        for (int i = 0; i <= 1; i++) {
            boolean landscape = (i == 1);

            LinearLayout navButtonLayout = (LinearLayout) (landscape ? rot90
                    .findViewById(R.id.nav_buttons) : rot0
                    .findViewById(R.id.nav_buttons));

            LinearLayout lightsOut = (LinearLayout) (landscape ? rot90
                    .findViewById(R.id.lights_out) : rot0
                    .findViewById(R.id.lights_out));

            // add left menu
            if (currentSetting != SHOW_DONT) {
                View leftmenuKey = generateKey(landscape, KEY_MENU_LEFT);
                addButton(navButtonLayout, leftmenuKey, landscape);
                addLightsOutButton(lightsOut, leftmenuKey, landscape, true);
            }

            for (int j = 0; j < mNumberOfButtons; j++) {
                Log.i(TAG, "Key" + j);
                View v = null;

                v = generateKey(landscape,mClickActions[j],mLongpressActions[j],
                		landscape ? mPortraitIcons[j] : mLandscapeIcons[j]);
               
                addButton(navButtonLayout, v, landscape);
                addLightsOutButton(lightsOut, v, landscape, false);

                if (j == (mNumberOfButtons - 1)) {
                        // which to skip
                } else if (mNumberOfButtons == 3) {
                        // add separator view here
                        View separator = new View(mContext);
                        separator.setLayoutParams(getSeparatorLayoutParams(landscape));
                        addButton(navButtonLayout, separator, landscape);
                        addLightsOutButton(lightsOut, separator, landscape, true);
                }
            }

    protected void updateResources() {
        final Resources res = mContext.getResources();
        mBackIcon = res.getDrawable(R.drawable.ic_sysbar_back);
        mBackLandIcon = res.getDrawable(R.drawable.ic_sysbar_back_land);
        mBackAltIcon = res.getDrawable(R.drawable.ic_sysbar_back_ime);
        mBackAltLandIcon = res.getDrawable(R.drawable.ic_sysbar_back_ime_land);
        mRecentIcon = res.getDrawable(R.drawable.ic_sysbar_recent);
        mRecentLandIcon = res.getDrawable(R.drawable.ic_sysbar_recent_land);
    }

    public class NavBarReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean edit = intent.getBooleanExtra("edit", false);
            boolean save = intent.getBooleanExtra("save", false);
            if (edit != EDIT_MODE) {
                EDIT_MODE = edit;
                if (EDIT_MODE) {
                    toggleButtonListener(false);
                    mEditBar.setupListeners();
                    mEditBar.updateKeys();
                } else {
                    mEditBar.dismissDialog();
                    if (save) {
                        mEditBar.saveKeys();
                    }
                    mEditBar.reInflate();
                    mEditBar = new NavbarEditor((ViewGroup) mCurrentView.findViewById(R.id.container), mVertical);
                    mEditBar.updateKeys();
                    toggleButtonListener(true);
                    if (save) {
                        mEditBar.updateLowLights(mCurrentView);
                    }
                    ((ViewGroup) mCurrentView.findViewById(R.id.mid_nav_buttons)).setLayoutTransition(
                            new LayoutTransition());
                }
            }
        }
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        updateResources();
	super.setLayoutDirection(layoutDirection);
    }

    private void addLightsOutButton(LinearLayout root, View v, boolean landscape, boolean empty) {

        ImageView addMe = new ImageView(mContext);
        addMe.setLayoutParams(v.getLayoutParams());
        addMe.setImageResource(empty ? R.drawable.ic_sysbar_lights_out_dot_large
                : R.drawable.ic_sysbar_lights_out_dot_small);
        addMe.setScaleType(ImageView.ScaleType.CENTER);
        addMe.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);

        if (landscape)
            root.addView(addMe, 0);
        else
            root.addView(addMe);

    }

    private void addButton(ViewGroup root, View addMe, boolean landscape) {
        if (landscape)
            root.addView(addMe, 0);
        else
            root.addView(addMe);
    }

    /*
     * TODO we can probably inflate each key from an XML would also be extremely useful to themers,
     * they may hate this for now
     */
    private View generateKey(boolean landscape, int keyId) {
    KeyButtonView v = null;
    Resources r = getResources();

    int btnWidth = 80;
    
     switch (keyId) {
        
        case KEY_MENU_RIGHT:
            v = new KeyButtonView(mContext, null);
            v.setLayoutParams(getLayoutParams(landscape, 40));

            v.setId(R.id.menu);
            v.setCode(KeyEvent.KEYCODE_MENU);
            v.setImageResource(landscape ? R.drawable.ic_sysbar_menu_land
                    : R.drawable.ic_sysbar_menu);
            v.setVisibility(View.INVISIBLE);
            v.setContentDescription(r.getString(R.string.accessibility_menu));
            v.setGlowBackground(landscape ? R.drawable.ic_sysbar_highlight_land
                    : R.drawable.ic_sysbar_highlight);
            return v;

        case KEY_MENU_LEFT:
            v = new KeyButtonView(mContext, null);
            v.setLayoutParams(getLayoutParams(landscape, 40));

            v.setId(R.id.menu_left);
            v.setCode(KeyEvent.KEYCODE_MENU);
            v.setImageResource(landscape ? R.drawable.ic_sysbar_menu_land
                    : R.drawable.ic_sysbar_menu);
            v.setVisibility(View.INVISIBLE);
            v.setContentDescription(r.getString(R.string.accessibility_menu));
            v.setGlowBackground(landscape ? R.drawable.ic_sysbar_highlight_land
                    : R.drawable.ic_sysbar_highlight);
            return v;

    }

    return null;
}
    
    private View generateKey(boolean landscape, String ClickAction, String Longpress, 
    			String IconUri) {
        KeyButtonView v = null;
        Resources r = getResources();

        int btnWidth = 80;
              
        v = new ExtensibleKeyButtonView(mContext,null,ClickAction,Longpress);
        v.setLayoutParams(getLayoutParams(landscape, btnWidth));
        v.setGlowBackground(landscape ? R.drawable.ic_sysbar_highlight_land
                : R.drawable.ic_sysbar_highlight);
        // the rest is for setting the icon (or custom icon)
        if (IconUri != null && IconUri.length() > 0) {
            File f = new File(Uri.parse(IconUri).getPath());
            if (f.exists())
                v.setImageDrawable(new BitmapDrawable(r, f.getAbsolutePath()));
        }

        if (IconUri != null && !IconUri.equals("")
                && IconUri.startsWith("file")) {
            // it's an icon the user chose from the gallery here
            File icon = new File(Uri.parse(IconUri).getPath());
            if (icon.exists())
                v.setImageDrawable(new BitmapDrawable(getResources(), icon.getAbsolutePath()));

        } else if (IconUri != null && !IconUri.equals("")) {
            // here they chose another app icon
            try {
            	PackageManager pm = getContext().getPackageManager();
                v.setImageDrawable(pm.getActivityIcon(Intent.parseUri(IconUri, 0)));
            } catch (NameNotFoundException e) {
                e.printStackTrace();
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        } else {
            // ok use default icons here
            v.setImageDrawable(getNavbarIconImage(landscape,ClickAction));
        }
        
        return v;
    }     

    public void notifyScreenOn(boolean screenOn) {
        mScreenOn = screenOn;
        setDisabledFlags(mDisabledFlags, true);
    }

    View.OnTouchListener mLightsOutListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                // even though setting the systemUI visibility below will turn these views
                // on, we need them to come up faster so that they can catch this motion
                // event
                setLowProfile(false, false, false);

                try {
                    mBarService.setSystemUiVisibility(0, View.SYSTEM_UI_FLAG_LOW_PROFILE);
                } catch (android.os.RemoteException ex) {
                }
            }
            return false;
        }
    };

    @Override
    public void setNavigationIconHints(int hints) {
        setNavigationIconHints(hints, false);
    }

    public void setNavigationIconHints(int hints, boolean force) {
        if (!force && hints == mNavigationIconHints) return;

        if (DEBUG) {
            android.widget.Toast.makeText(mContext,
                "Navigation icon hints = " + hints,
                500).show();
        }

        mNavigationIconHints = hints;

        View button = mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_HOME);
        if (button != null) {
            button.setAlpha(
                    (0 != (hints & StatusBarManager.NAVIGATION_HINT_HOME_NOP)) ? 0.5f : 1.0f);
        }
        button = mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_RECENT);
        if (button != null) {
            button.setAlpha(
                    (0 != (hints & StatusBarManager.NAVIGATION_HINT_RECENT_NOP)) ? 0.5f : 1.0f);
        }
        button = mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_BACK);
        if (button != null) {
            button.setAlpha(
                    (0 != (hints & StatusBarManager.NAVIGATION_HINT_BACK_NOP)) ? 0.5f : 1.0f);
            ((ImageView)button).setImageDrawable(
                    (0 != (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT))
                    ? (mVertical ? mBackAltLandIcon : mBackAltIcon)
                            : (mVertical ? mBackLandIcon : mBackIcon));
        }

        setDisabledFlags(mDisabledFlags, true);
    }

    @Override
    public void setDisabledFlags(int disabledFlags) {
        setDisabledFlags(disabledFlags, false);
    }

    public void setDisabledFlags(int disabledFlags, boolean force) {
        if (!force && mDisabledFlags == disabledFlags) return;

        mDisabledFlags = disabledFlags;

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        final boolean disableRecent = ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
        final boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);
        final boolean disableSearch = ((disabledFlags & View.STATUS_BAR_DISABLE_SEARCH) != 0);

        if (SLIPPERY_WHEN_DISABLED) {
            setSlippery(disableHome && disableRecent && disableBack && disableSearch);
        }

        if (!mScreenOn && mCurrentView != null) {
            ViewGroup navButtons = (ViewGroup) mCurrentView.findViewById(R.id.nav_buttons);
            LayoutTransition lt = navButtons == null ? null : navButtons.getLayoutTransition();
            if (lt != null) {
                lt.disableTransitionType(
                        LayoutTransition.CHANGE_APPEARING | LayoutTransition.CHANGE_DISAPPEARING |
                        LayoutTransition.APPEARING | LayoutTransition.DISAPPEARING);
            }
        }

        setButtonWithTagVisibility(NavbarEditor.NAVBAR_BACK, disableBack ? View.INVISIBLE : View.VISIBLE);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_HOME, disableHome ? View.INVISIBLE : View.VISIBLE);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_RECENT, disableRecent ? View.INVISIBLE : View.VISIBLE);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_RECENT, disableRecent ? View.INVISIBLE : View.VISIBLE);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_ALWAYS_MENU, disableRecent ? View.INVISIBLE : View.VISIBLE);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_MENU_BIG, disableRecent ? View.INVISIBLE : View.VISIBLE);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_SEARCH, disableRecent ? View.INVISIBLE : View.VISIBLE);
        getSearchLight().setVisibility((disableHome && !disableSearch) ? View.VISIBLE : View.GONE);
    }

    public void setSlippery(boolean newSlippery) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp != null) {
            boolean oldSlippery = (lp.flags & WindowManager.LayoutParams.FLAG_SLIPPERY) != 0;
            if (!oldSlippery && newSlippery) {
                lp.flags |= WindowManager.LayoutParams.FLAG_SLIPPERY;
            } else if (oldSlippery && !newSlippery) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_SLIPPERY;
            } else {
                return;
            }
            WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(this, lp);
        }
    }

    @Override
    public void setMenuVisibility(final boolean show) {
        setMenuVisibility(show, false);
    }

    public void setMenuVisibility(final boolean show, final boolean force) {
        if (!force && mShowMenu == show) return;

        mShowMenu = show;

        setButtonWithTagVisibility(NavbarEditor.NAVBAR_CONDITIONAL_MENU, mShowMenu ? View.VISIBLE : View.INVISIBLE);
    }

    public void setLowProfile(final boolean lightsOut) {
        setLowProfile(lightsOut, true, false);
    }

    public void setLowProfile(final boolean lightsOut, final boolean animate, final boolean force) {
        if (!force && lightsOut == mLowProfile) return;

        mLowProfile = lightsOut;

        if (DEBUG) Slog.d(TAG, "setting lights " + (lightsOut?"out":"on"));

        final View navButtons = mCurrentView.findViewById(R.id.nav_buttons);
        final View lowLights = mCurrentView.findViewById(R.id.lights_out);

        // ok, everyone, stop it right there
        navButtons.animate().cancel();
        lowLights.animate().cancel();

        if (!animate) {
            navButtons.setAlpha(lightsOut ? 0f : 1f);

            lowLights.setAlpha(lightsOut ? 1f : 0f);
            lowLights.setVisibility(lightsOut ? View.VISIBLE : View.GONE);
        } else {
            navButtons.animate()
                .alpha(lightsOut ? 0f : 1f)
                .setDuration(lightsOut ? 750 : 250)
                .start();

            lowLights.setOnTouchListener(mLightsOutListener);
            if (lowLights.getVisibility() == View.GONE) {
                lowLights.setAlpha(0f);
                lowLights.setVisibility(View.VISIBLE);
            }
            lowLights.animate()
                .alpha(lightsOut ? 1f : 0f)
                .setDuration(lightsOut ? 750 : 250)
                .setInterpolator(new AccelerateInterpolator(2.0f))
                .setListener(lightsOut ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator _a) {
                        lowLights.setVisibility(View.GONE);
                    }
                })
                .start();
        }
    }

    public void setHidden(final boolean hide) {
        if (hide == mHidden) return;

        mHidden = hide;
        Slog.d(TAG,
            (hide ? "HIDING" : "SHOWING") + " navigation bar");

        // bring up the lights no matter what
        setLowProfile(false);
    }

    @Override
    public void onFinishInflate() {
        mRotatedViews[Configuration.ORIENTATION_PORTRAIT] = findViewById(R.id.rot0);
        mRotatedViews[Configuration.ORIENTATION_LANDSCAPE] = findViewById(R.id.rot90);
        mCurrentView = mRotatedViews[mContext.getResources().getConfiguration().orientation];
    }

    public void reorient() {
        int rot = mContext.getResources().getConfiguration().orientation;
        for (int i=1; i<3; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }
        mCurrentView = mRotatedViews[rot];
        mCurrentView.setVisibility(View.VISIBLE);
        if (NavbarEditor.isDevicePhone(mContext)) {
            rot = mDisplay.getRotation();
            mVertical = (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270);
            mDelegateHelper.setSwapXY(mVertical);
        } else {
            mVertical = getWidth() > 0 && getHeight() > getWidth();
        }
        mEditBar = new NavbarEditor((ViewGroup) mCurrentView.findViewById(R.id.container), mVertical);
        mEditBar.updateKeys();
        mEditBar.updateLowLights(mCurrentView);
        toggleButtonListener(true);

        mDeadZone = (DeadZone) mCurrentView.findViewById(R.id.deadzone);

        // force the low profile & disabled states into compliance
        setLowProfile(mLowProfile, false, true /* force */);
        setDisabledFlags(mDisabledFlags, true /* force */);
        setMenuVisibility(mShowMenu, true /* force */);

        if (DEBUG) {
            Slog.d(TAG, "reorient(): rot=" + mDisplay.getRotation());
        }

        setNavigationIconHints(mNavigationIconHints, true);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        ViewGroup mid_nav = (ViewGroup) mCurrentView.findViewById(R.id.mid_nav_buttons);
        View vViews[] = new View[mid_nav.getChildCount()];
        for (int cc = 0;cc < mid_nav.getChildCount(); cc++) {
            vViews[cc] = mid_nav.getChildAt(cc);
        }
        mDelegateHelper.setInitialTouchRegion(vViews);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (DEBUG) Slog.d(TAG, String.format(
                    "onSizeChanged: (%dx%d) old: (%dx%d)", w, h, oldw, oldh));

        final boolean newVertical = w > 0 && h > w;
        System.out.println(newVertical + " vs " + mVertical);
        if (newVertical != mVertical) {
            mVertical = newVertical;
            //Slog.v(TAG, String.format("onSizeChanged: h=%d, w=%d, vert=%s", h, w, mVertical?"y":"n"));
            reorient();
        }

        postCheckForInvalidLayout("sizeChanged");
        super.onSizeChanged(w, h, oldw, oldh);
    }

    /*
    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
        if (DEBUG) Slog.d(TAG, String.format(
                    "onLayout: %s (%d,%d,%d,%d)", 
                    changed?"changed":"notchanged", left, top, right, bottom));
        super.onLayout(changed, left, top, right, bottom);
    }

    // uncomment this for extra defensiveness in WORKAROUND_INVALID_LAYOUT situations: if all else
    // fails, any touch on the display will fix the layout.
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Slog.d(TAG, "onInterceptTouchEvent: " + ev.toString());
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            postCheckForInvalidLayout("touch");
        }
        return super.onInterceptTouchEvent(ev);
    }
    */
        

    private String getResourceName(int resId) {
        if (resId != 0) {
            final android.content.res.Resources res = mContext.getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    private void postCheckForInvalidLayout(final String how) {
        mHandler.obtainMessage(MSG_CHECK_INVALID_LAYOUT, 0, 0, how).sendToTarget();
    }

    private static String visibilityToString(int vis) {
        switch (vis) {
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return "VISIBLE";
        }
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            //resolver.registerContentObserver(
            //        Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_BUTTONS), false,
            //        this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.MENU_LOCATION), false,
                    this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.MENU_VISIBILITY), false,
                    this);
            
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_BUTTONS_QTY), false,
                    this);
            
            for (int j = 0; j < 5; j++) { // watch all 5 settings for changes.
            	resolver.registerContentObserver(
            		Settings.System.getUriFor(Settings.System.NAVIGATION_CUSTOM_ACTIVITIES[j]), false,
            		this);
            	resolver.registerContentObserver(
            		Settings.System.getUriFor(Settings.System.NAVIGATION_LONGPRESS_ACTIVITIES[j]), false,
                    this);
            	resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_CUSTOM_APP_ICONS[j]), false,
                    this);
            	resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_LANDSCAPE_APP_ICONS[j]), false,
                    this);
            }
            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    protected void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        //userNavBarButtons = Settings.System.getString(resolver,
        //        Settings.System.NAVIGATION_BAR_BUTTONS);

        currentSetting = Settings.System.getInt(resolver,
                Settings.System.MENU_LOCATION, SHOW_RIGHT_MENU);

        currentVisibility = Settings.System.getInt(resolver,
                Settings.System.MENU_VISIBILITY, VISIBILITY_SYSTEM);
        
        mNumberOfButtons = Settings.System.getInt(resolver,
                Settings.System.NAVIGATION_BAR_BUTTONS_QTY, StockButtonsQty);

        for (int j = 0; j < mNumberOfButtons; j++) {
        	mClickActions[j] = Settings.System.getString(resolver,
                    Settings.System.NAVIGATION_CUSTOM_ACTIVITIES[j]);
        	
        	mLongpressActions[j] = Settings.System.getString(resolver,
                    Settings.System.NAVIGATION_LONGPRESS_ACTIVITIES[j]);
        	
        	mPortraitIcons[j]= Settings.System.getString(resolver,
                    Settings.System.NAVIGATION_CUSTOM_APP_ICONS[j]);
        	
        	mLandscapeIcons[j]= Settings.System.getString(resolver,
                    Settings.System.NAVIGATION_LANDSCAPE_APP_ICONS[j]);     
        }
        makeBar();

    }
    
    private Drawable getNavbarIconImage(boolean landscape,String uri) {
    	
        if (uri == null)
            return getResources().getDrawable(R.drawable.ic_null);
        
        Log.d (TAG,"GetNavBarIcon:" + uri);

        if (uri.startsWith("**")) {
            if (uri.equals(ACTION_HOME)) {
            	if (!landscape)
            		return getResources().getDrawable(R.drawable.ic_sysbar_home);
            	else
            		return getResources().getDrawable(R.drawable.ic_sysbar_home_land);
            } else if (uri.equals(ACTION_BACK)) {
            	if (!landscape)
            		return getResources().getDrawable(R.drawable.ic_sysbar_back);
            	else
            		return getResources().getDrawable(R.drawable.ic_sysbar_back_land);
            } else if (uri.equals(ACTION_RECENTS)) {
            	if (!landscape)
            		return getResources().getDrawable(R.drawable.ic_sysbar_recent);
            	else
            		return getResources().getDrawable(R.drawable.ic_sysbar_recent_land);
            } else if (uri.equals(ACTION_SEARCH)) {
            	if (!landscape)
            		return getResources().getDrawable(R.drawable.ic_sysbar_search);
            	else
            		return getResources().getDrawable(R.drawable.ic_sysbar_search_land);
            } else if (uri.equals(ACTION_MENU)) {
            	if (!landscape)
            		return getResources().getDrawable(R.drawable.ic_sysbar_menu_big);
            	else
            		return getResources().getDrawable(R.drawable.ic_sysbar_menu_land_big);
            } else if (uri.equals(ACTION_KILL)) {
            	if (!landscape)
            		return getResources().getDrawable(R.drawable.ic_sysbar_killtask);
            	else
            		return getResources().getDrawable(R.drawable.ic_sysbar_killtask_land);
            } else if (uri.equals(ACTION_POWER)) {
            	if (!landscape)
            		return getResources().getDrawable(R.drawable.ic_sysbar_power);
            	else
            		return getResources().getDrawable(R.drawable.ic_sysbar_power_land);
            }
        } else {
            try {
                return mContext.getPackageManager().getActivityIcon(Intent.parseUri(uri, 0));
            } catch (NameNotFoundException e) {
                e.printStackTrace();
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }

        return getResources().getDrawable(R.drawable.ic_null);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationBarView {");
        final Rect r = new Rect();
        final Point size = new Point();
        mDisplay.getRealSize(size);

        pw.println(String.format("      this: " + PhoneStatusBar.viewInfo(this)
                        + " " + visibilityToString(getVisibility())));

        getWindowVisibleDisplayFrame(r);
        final boolean offscreen = r.right > size.x || r.bottom > size.y;
        pw.println("      window: " 
                + r.toShortString()
                + " " + visibilityToString(getWindowVisibility())
                + (offscreen ? " OFFSCREEN!" : ""));

        pw.println(String.format("      mCurrentView: id=%s (%dx%d) %s",
                        getResourceName(mCurrentView.getId()),
                        mCurrentView.getWidth(), mCurrentView.getHeight(),
                        visibilityToString(mCurrentView.getVisibility())));

        pw.println(String.format("      disabled=0x%08x vertical=%s hidden=%s low=%s menu=%s",
                        mDisabledFlags,
                        mVertical ? "true" : "false",
                        mHidden ? "true" : "false",
                        mLowProfile ? "true" : "false",
                        mShowMenu ? "true" : "false"));

        final View back = mCurrentView.findViewWithTag("back");
        final View home = mCurrentView.findViewWithTag("home");
        final View recent = mCurrentView.findViewWithTag("recent");

        pw.println("      back: "
                + PhoneStatusBar.viewInfo(back)
                + " " + visibilityToString(back.getVisibility())
                );
        pw.println("      home: "
                + PhoneStatusBar.viewInfo(home)
                + " " + visibilityToString(home.getVisibility())
                );
        pw.println("      rcnt: "
                + PhoneStatusBar.viewInfo(recent)
                + " " + visibilityToString(recent.getVisibility())
                );
        pw.println("    }");
    }

}
