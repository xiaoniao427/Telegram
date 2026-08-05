/*
 * ponytail: simple crash detail page — shows error with a copy button
 */

package org.telegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class CrashDetailActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        String crashText = getCrashText();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(0xFF1A1A1A);

        // title
        TextView title = new TextView(this);
        title.setText("应用发生异常");
        title.setTextColor(0xFFFF5555);
        title.setTextSize(20);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        // scrollable error text
        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        scrollView.setLayoutParams(scrollParams);

        TextView errorText = new TextView(this);
        errorText.setText(crashText != null ? crashText : "无错误信息");
        errorText.setTextColor(0xFFCCCCCC);
        errorText.setTextSize(13);
        errorText.setMovementMethod(new ScrollingMovementMethod());
        errorText.setPadding(0, 0, 0, dp(12));
        scrollView.addView(errorText);
        root.addView(scrollView);

        // button row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        Button copyBtn = new Button(this);
        copyBtn.setText("复制错误信息");
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("crash", crashText != null ? crashText : ""));
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });

        Button closeBtn = new Button(this);
        closeBtn.setText("关闭");
        closeBtn.setOnClickListener(v -> finish());

        btnRow.addView(copyBtn);
        btnRow.addView(closeBtn);
        root.addView(btnRow);

        setContentView(root);
    }

    private String getCrashText() {
        try {
            SharedPreferences prefs = getSharedPreferences("systemConfig", Context.MODE_PRIVATE);
            return prefs.getString("last_crash", null);
        } catch (Throwable e) {
            return null;
        }
    }

    private int dp(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}