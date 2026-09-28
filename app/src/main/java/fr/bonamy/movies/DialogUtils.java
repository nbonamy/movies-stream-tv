package fr.bonamy.movies;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.StringRes;
import androidx.core.content.res.ResourcesCompat;




import java.util.ArrayList;
import java.util.List;

public class DialogUtils {

  private static final class DialogAction {
    final CharSequence label;
    final int which;
    final DialogInterface.OnClickListener listener;
    final boolean autoDismiss;
    final boolean selected;

    DialogAction(CharSequence label, int which, DialogInterface.OnClickListener listener, boolean autoDismiss, boolean selected) {
      this.label = label;
      this.which = which;
      this.listener = listener;
      this.autoDismiss = autoDismiss;
      this.selected = selected;
    }
  }

  private static final class StyledAlertDialog extends AlertDialog {
    private final View mContent;
    private final View mPanel;
    private boolean mDismissing;
    private boolean mEntranceStarted;

    StyledAlertDialog(Context context, int themeResId, View content) {
      super(context, themeResId);
      mContent = content;
      mPanel = content.findViewById(R.id.dialog_panel);
      requestWindowFeature(Window.FEATURE_NO_TITLE);
      primeEntranceState();
      Window window = getWindow();
      if (window != null) {
        window.setWindowAnimations(0);
      }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      setContentView(mContent);
      configureWindow(this);
    }

    @Override
    protected void onStart() {
      // Window geometry must be final before WindowManager attaches the decor.
      // Changing MATCH_PARENT/gravity from onShow makes the centered panel
      // appear to travel horizontally from Android's initial dialog bounds.
      super.onStart();
      configureWindow(this);
    }

    void animateIn() {
      if (mEntranceStarted) return;
      mEntranceStarted = true;
      mPanel.animate().cancel();
      mPanel.animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(180L)
        .setInterpolator(new DecelerateInterpolator())
        .start();
    }

    private void primeEntranceState() {
      float offset = getContext().getResources().getDimension(R.dimen.tv_dialog_animation_offset);
      mPanel.setAlpha(0f);
      mPanel.setTranslationX(0f);
      mPanel.setTranslationY(offset);
    }

    @Override
    public void dismiss() {
      if (!isShowing() || mDismissing || mPanel.getWindowToken() == null) {
        dismissImmediately();
        return;
      }

      mDismissing = true;
      float offset = getContext().getResources().getDimension(R.dimen.tv_dialog_animation_offset);
      mPanel.animate().cancel();
      mPanel.animate()
        .alpha(0f)
        .translationY(offset)
        .setDuration(120L)
        .setInterpolator(new AccelerateInterpolator())
        .setListener(new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            dismissImmediately();
          }
        })
        .start();
    }

    private void dismissImmediately() {
      super.dismiss();
    }
  }

  public static class DialogBuilder extends AlertDialog.Builder {

    private final int mThemeResId;
    private CharSequence mTitle;
    private CharSequence mMessage;
    private View mCustomView;
    private final List<DialogAction> mItemActions = new ArrayList<>();
    private DialogAction mPositiveAction;
    private DialogAction mNegativeAction;
    private DialogAction mNeutralAction;
    private boolean mCancelable = true;
    private DialogInterface.OnKeyListener mOnKeyListener;

    public DialogBuilder(Context context, int themeResId) {
      super(context, themeResId);
      mThemeResId = themeResId;
    }

    @Override
    public DialogBuilder setTitle(CharSequence title) {
      mTitle = title;
      return this;
    }

    @Override
    public DialogBuilder setTitle(@StringRes int titleId) {
      return setTitle(getContext().getText(titleId));
    }

    @Override
    public DialogBuilder setMessage(CharSequence message) {
      mMessage = message;
      return this;
    }

    @Override
    public DialogBuilder setMessage(@StringRes int messageId) {
      return setMessage(getContext().getText(messageId));
    }

    @Override
    public DialogBuilder setView(View view) {
      mCustomView = view;
      return this;
    }

    @Override
    public DialogBuilder setView(int layoutResId) {
      return setView(LayoutInflater.from(getContext()).inflate(layoutResId, null));
    }

    @Override
    public DialogBuilder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
      setItemActions(items, -1, listener, true);
      return this;
    }

    @Override
    public DialogBuilder setItems(int itemsId, DialogInterface.OnClickListener listener) {
      return setItems(getContext().getResources().getTextArray(itemsId), listener);
    }

    @Override
    public DialogBuilder setSingleChoiceItems(CharSequence[] items, int checkedItem, DialogInterface.OnClickListener listener) {
      setItemActions(items, checkedItem, listener, false);
      return this;
    }

    @Override
    public DialogBuilder setSingleChoiceItems(int itemsId, int checkedItem, DialogInterface.OnClickListener listener) {
      return setSingleChoiceItems(getContext().getResources().getTextArray(itemsId), checkedItem, listener);
    }

    @Override
    public DialogBuilder setSingleChoiceItems(ListAdapter adapter, int checkedItem, DialogInterface.OnClickListener listener) {
      CharSequence[] items = new CharSequence[adapter.getCount()];
      for (int i = 0; i < adapter.getCount(); i++) {
        Object item = adapter.getItem(i);
        items[i] = item instanceof CharSequence ? (CharSequence) item : String.valueOf(item);
      }
      return setSingleChoiceItems(items, checkedItem, listener);
    }

    @Override
    public DialogBuilder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
      mPositiveAction = buttonAction(text, DialogInterface.BUTTON_POSITIVE, listener);
      return this;
    }

    @Override
    public DialogBuilder setPositiveButton(@StringRes int textId, DialogInterface.OnClickListener listener) {
      return setPositiveButton(getContext().getText(textId), listener);
    }

    @Override
    public DialogBuilder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
      mNegativeAction = buttonAction(text, DialogInterface.BUTTON_NEGATIVE, listener);
      return this;
    }

    @Override
    public DialogBuilder setNegativeButton(@StringRes int textId, DialogInterface.OnClickListener listener) {
      return setNegativeButton(getContext().getText(textId), listener);
    }

    @Override
    public DialogBuilder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
      mNeutralAction = buttonAction(text, DialogInterface.BUTTON_NEUTRAL, listener);
      return this;
    }

    @Override
    public DialogBuilder setNeutralButton(@StringRes int textId, DialogInterface.OnClickListener listener) {
      return setNeutralButton(getContext().getText(textId), listener);
    }

    @Override
    public DialogBuilder setCancelable(boolean cancelable) {
      mCancelable = cancelable;
      return this;
    }

    @Override
    public DialogBuilder setOnKeyListener(DialogInterface.OnKeyListener onKeyListener) {
      mOnKeyListener = onKeyListener;
      return this;
    }

    @Override
    public DialogBuilder setIcon(int iconId) {
      // The Mediastation sheet deliberately uses a clean accent rail instead
      // of platform alert icons.
      return this;
    }

    @Override
    public AlertDialog create() {
      LayoutInflater inflater = LayoutInflater.from(getContext());
      View content = inflater.inflate(R.layout.dialog_tv_action, null);
      TextView titleView = content.findViewById(R.id.dialog_title);
      ScrollView messageScroll = content.findViewById(R.id.dialog_message_scroll);
      TextView messageView = content.findViewById(R.id.dialog_message);
      FrameLayout customContainer = content.findViewById(R.id.dialog_custom_content);
      ScrollView actionScroll = content.findViewById(R.id.dialog_action_scroll);
      LinearLayout actionContainer = content.findViewById(R.id.dialog_actions);

      titleView.setText(mTitle == null ? "" : mTitle);
      titleView.setVisibility(isBlank(mTitle) ? View.GONE : View.VISIBLE);
      messageView.setText(mMessage == null ? "" : mMessage);
      messageScroll.setVisibility(isBlank(mMessage) ? View.GONE : View.VISIBLE);

      if (mCustomView != null) {
        if (mCustomView.getParent() instanceof ViewGroup) {
          ((ViewGroup) mCustomView.getParent()).removeView(mCustomView);
        }
        customContainer.addView(mCustomView);
        customContainer.setVisibility(View.VISIBLE);
      }

      List<DialogAction> actions = new ArrayList<>(mItemActions);
      if (mNeutralAction != null) actions.add(mNeutralAction);
      if (mNegativeAction != null) actions.add(mNegativeAction);
      if (mPositiveAction != null) actions.add(mPositiveAction);

      StyledAlertDialog dialog = new StyledAlertDialog(getContext(), mThemeResId, content);
      dialog.setCancelable(mCancelable);
      dialog.setOnKeyListener(mOnKeyListener);

      View initialAction = null;
      for (int index = 0; index < actions.size(); index++) {
        DialogAction action = actions.get(index);
        TextView row = (TextView) inflater.inflate(R.layout.item_tv_dialog_action, actionContainer, false);
        if (index > 0) {
          LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.getLayoutParams();
          params.topMargin = row.getResources().getDimensionPixelSize(R.dimen.tv_dialog_action_spacing);
          row.setLayoutParams(params);
        }
        row.setId(View.generateViewId());
        row.setText(action.label);
        row.setSelected(action.selected);
        row.setOnClickListener(view -> {
          if (action.autoDismiss) {
            dialog.dismiss();
          }
          if (action.listener != null) {
            action.listener.onClick(dialog, action.which);
          }
        });
        actionContainer.addView(row);
        if (initialAction == null || action.selected) {
          initialAction = row;
        }
      }
      actionScroll.setVisibility(actions.isEmpty() ? View.GONE : View.VISIBLE);
      capActionListHeight(actionScroll, actions.size());

      View finalInitialAction = initialAction;
      dialog.setOnShowListener(ignored -> {
        dialog.animateIn();
        capScrollableMessageHeight(messageScroll);
        setupScrollableMessageNavigation(messageScroll, finalInitialAction);
        if (messageScroll.getVisibility() == View.VISIBLE) {
          messageScroll.requestFocus();
        } else if (mCustomView != null && mCustomView.requestFocus()) {
          // The custom view owns focus, notably MovieDB text entry.
        } else if (finalInitialAction != null) {
          finalInitialAction.requestFocus();
        }
      });
      return dialog;
    }

    private DialogAction buttonAction(CharSequence label, int which, DialogInterface.OnClickListener listener) {
      return label == null ? null : new DialogAction(label, which, listener, true, false);
    }

    private void setItemActions(CharSequence[] items, int checkedItem, DialogInterface.OnClickListener listener, boolean autoDismiss) {
      mItemActions.clear();
      if (items == null) return;
      for (int i = 0; i < items.length; i++) {
        mItemActions.add(new DialogAction(items[i], i, listener, autoDismiss, i == checkedItem));
      }
    }

    private void capActionListHeight(ScrollView actionScroll, int actionCount) {
      if (actionCount == 0) return;
      int rowHeight = actionScroll.getResources().getDimensionPixelSize(R.dimen.tv_dialog_action_height);
      int spacing = actionScroll.getResources().getDimensionPixelSize(R.dimen.tv_dialog_action_spacing);
      int desiredHeight = actionCount * rowHeight + Math.max(0, actionCount - 1) * spacing;
      int maxHeight = actionScroll.getResources().getDimensionPixelSize(R.dimen.tv_dialog_actions_max_height);
      if (desiredHeight > maxHeight) {
        actionScroll.getLayoutParams().height = maxHeight;
      }
    }
  }

  public static DialogUtils.DialogBuilder getDialogBuilder(Context context) {
    return new DialogUtils.DialogBuilder(context, R.style.LeanbackDialog);
  }

  public static DialogUtils.DialogBuilder getDialogBuilder(Context context, String title) {
    return DialogUtils.getDialogBuilder(context).setTitle(title);
  }

  public static DialogUtils.DialogBuilder getDialogBuilder(Context context, @StringRes int title) {
    return DialogUtils.getDialogBuilder(context, context.getString(title));
  }

  private static boolean isBlank(CharSequence value) {
    return value == null || value.toString().trim().isEmpty();
  }

  private static void configureWindow(Dialog dialog) {
    Window window = dialog.getWindow();
    if (window == null) return;
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    window.setWindowAnimations(0);
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
    WindowManager.LayoutParams attributes = window.getAttributes();
    attributes.dimAmount = 0.72f;
    attributes.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
    attributes.y = 0;
    window.setAttributes(attributes);
  }

  private static void capScrollableMessageHeight(ScrollView messageScroll) {
    if (messageScroll.getVisibility() != View.VISIBLE) return;
    messageScroll.post(() -> {
      int maxHeight = messageScroll.getResources().getDimensionPixelSize(R.dimen.tv_dialog_message_max_height);
      if (messageScroll.getHeight() > maxHeight) {
        messageScroll.getLayoutParams().height = maxHeight;
        messageScroll.requestLayout();
      }
    });
  }

  private static void setupScrollableMessageNavigation(ScrollView messageScroll, View firstAction) {
    if (messageScroll.getVisibility() != View.VISIBLE) return;
    if (firstAction != null) {
      messageScroll.setNextFocusDownId(firstAction.getId());
      firstAction.setNextFocusUpId(R.id.dialog_message_scroll);
    }
    messageScroll.setOnKeyListener((view, keyCode, event) -> {
      if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
      ScrollView scrollView = (ScrollView) view;
      int scrollDelta = scrollView.getResources().getDimensionPixelSize(R.dimen.tv_dialog_message_scroll_step);
      if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
        if (scrollView.getScrollY() <= 0) return false;
        scrollView.smoothScrollBy(0, -scrollDelta);
        return true;
      }
      if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
        View child = scrollView.getChildAt(0);
        if (child == null) return false;
        int maxScroll = Math.max(0, child.getHeight() - scrollView.getHeight());
        if (maxScroll <= 0 && firstAction == null) return true;
        if (scrollView.getScrollY() >= maxScroll) return false;
        scrollView.smoothScrollBy(0, scrollDelta);
        return true;
      }
      return false;
    });
  }

  public static void setupTitle(Context context, Dialog dialog) {
    setupTitle(context, dialog, true);
  }

  public static void setupTitle(Context context, Dialog dialog, boolean setTitleColor) {
    Window window = dialog.getWindow();
    if (window == null) return;
    TextView title = window.findViewById(context.getResources().getIdentifier("alertTitle", "id", "android"));
    if (title != null) {
      title.setTypeface(ResourcesCompat.getFont(context, R.font.theme), Typeface.BOLD);
      if (setTitleColor) {
        title.setTextColor(Color.argb(255, 145, 200, 195));
      }
    }
  }
}
