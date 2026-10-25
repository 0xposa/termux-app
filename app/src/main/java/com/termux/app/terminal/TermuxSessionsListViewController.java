package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

public class TermuxSessionsListViewController extends ArrayAdapter<TermuxSession> implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    final TermuxActivity mActivity;

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList) {
        super(activity.getApplicationContext(), R.layout.item_terminal_sessions_list, sessionList);
        this.mActivity = activity;
    }

    @SuppressLint("SetTextI18n")
    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View sessionRowView = convertView;
        if (sessionRowView == null) {
            LayoutInflater inflater = mActivity.getLayoutInflater();
            sessionRowView = inflater.inflate(R.layout.item_terminal_sessions_list, parent, false);
        }

        TermuxSession termuxSession = getItem(position);
        TerminalSession sessionAtRow = (termuxSession != null) ? termuxSession.getTerminalSession() : null;
        if (sessionAtRow == null) {
            return sessionRowView;
        }

        View itemRoot = sessionRowView.findViewById(R.id.session_item_root);
        View statusIndicator = sessionRowView.findViewById(R.id.session_status_indicator);
        TextView numberView = sessionRowView.findViewById(R.id.session_number);
        TextView titleView = sessionRowView.findViewById(R.id.session_title);
        TextView subtitleView = sessionRowView.findViewById(R.id.session_subtitle);
        TextView activeBadge = sessionRowView.findViewById(R.id.session_active_badge);
        ImageButton closeButton = sessionRowView.findViewById(R.id.session_close_button);

        TerminalSession currentSession = mActivity.getCurrentSession();
        boolean isCurrent = (currentSession == sessionAtRow);

        if (itemRoot != null) {
            itemRoot.setBackground(ContextCompat.getDrawable(mActivity,
                isCurrent ? R.drawable.bg_session_card_active : R.drawable.bg_session_card));
        }

        if (activeBadge != null) {
            activeBadge.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
        }

        boolean sessionRunning = sessionAtRow.isRunning();
        if (statusIndicator != null) {
            statusIndicator.setBackgroundTintList(ContextCompat.getColorStateList(mActivity,
                sessionRunning ? R.color.m3_dark_badge_running : R.color.m3_dark_badge_exited));
        }

        if (numberView != null) {
            numberView.setText("#" + (position + 1));
        }

        String name = sessionAtRow.mSessionName;
        String sessionTitle = sessionAtRow.getTitle();

        if (titleView != null) {
            String displayName = !TextUtils.isEmpty(name) ? name : ("Session " + (position + 1));
            titleView.setText(displayName);
            if (sessionRunning) {
                titleView.setPaintFlags(titleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                titleView.setTextColor(ContextCompat.getColor(mActivity, R.color.m3_dark_on_surface));
            } else {
                titleView.setPaintFlags(titleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                titleView.setTextColor(ContextCompat.getColor(mActivity, R.color.m3_dark_on_surface_variant));
            }
        }

        if (subtitleView != null) {
            String subText;
            if (!TextUtils.isEmpty(sessionTitle)) {
                subText = sessionTitle;
            } else if (sessionAtRow.getCwd() != null) {
                subText = sessionAtRow.getCwd();
            } else {
                subText = sessionRunning ? "Running" : "Exited (" + sessionAtRow.getExitStatus() + ")";
            }
            subtitleView.setText(subText);
        }

        if (closeButton != null) {
            closeButton.setOnClickListener(v -> {
                if (sessionAtRow.isRunning()) {
                    new AlertDialog.Builder(mActivity)
                        .setTitle(R.string.title_confirm_kill_process)
                        .setMessage(mActivity.getString(R.string.action_kill_process, sessionAtRow.getPid()))
                        .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                            sessionAtRow.finishIfRunning();
                            mActivity.getTermuxTerminalSessionClient().removeFinishedSession(sessionAtRow);
                            notifyDataSetChanged();
                        })
                        .setNegativeButton(android.R.string.no, null)
                        .show();
                } else {
                    mActivity.getTermuxTerminalSessionClient().removeFinishedSession(sessionAtRow);
                    notifyDataSetChanged();
                }
            });
        }

        return sessionRowView;
    }

    @Override
    public void notifyDataSetChanged() {
        super.notifyDataSetChanged();
        TextView sessionCountView = mActivity.findViewById(R.id.drawer_session_count);
        if (sessionCountView != null) {
            int count = getCount();
            sessionCountView.setText(count + (count == 1 ? " Session" : " Sessions"));
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        TermuxSession clickedSession = getItem(position);
        if (clickedSession != null && clickedSession.getTerminalSession() != null) {
            mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
        }
        mActivity.getDrawer().closeDrawers();
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        final TermuxSession selectedSession = getItem(position);
        if (selectedSession != null && selectedSession.getTerminalSession() != null) {
            mActivity.getTermuxTerminalSessionClient().renameSession(selectedSession.getTerminalSession());
        }
        return true;
    }

}
