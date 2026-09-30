package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.HashMap;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.AccountSelectCell;
import org.telegram.ui.Components.*;

public final class SafeLinkServersActivity extends BaseFragment {
    private UniversalRecyclerView list;
    private final HashMap<Integer, Runnable> actions = new HashMap<>();
    private boolean discovering;

    @Override
    public View createView(Context context) {
        actionBar.setTitle("服务器与账号");
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        FrameLayout content = new FrameLayout(context);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        list = new UniversalRecyclerView(this, this::fill, (item, view, position, x, y) -> {
            Runnable action = actions.get(item.id);
            if (action != null) action.run();
        }, null);
        list.setSections();
        content.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(list);
        return fragmentView = content;
    }

    private void row(ArrayList<UItem> items, String text, String value, Runnable action) {
        int id = actions.size() + 1;
        actions.put(id, action);
        items.add(UItem.asButton(id, text, value));
    }

    private void fill(ArrayList<UItem> items, UniversalAdapter adapter) {
        actions.clear();
        try {
            for (SafeLinkServer server : SafeLinkServers.all()) {
                items.add(UItem.asHeader(server.name));
                row(items, server.host + ":" + server.port, "", () -> alert("公钥 SHA-256\n" + server.id + "\n\nMTProto 指纹\n" + server.fingerprint));
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    final int account = a;
                    if (UserConfig.getInstance(a).isClientActivated() && SafeLinkServers.account(a).id.equals(server.id)) {
                        AccountSelectCell cell = new AccountSelectCell(getContext(), false);
                        cell.setAccount(account, true);
                        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                        cell.setOnClickListener(view -> {
                            if (LaunchActivity.instance != null) LaunchActivity.instance.switchToAccount(account, true);
                        });
                        items.add(UItem.asCustom(cell));
                    }
                }
                row(items, "添加账号", "", () -> addAccount(server));
                items.add(UItem.asShadow(null));
            }
            row(items, discovering ? "正在验证服务器…" : "添加服务器", "", this::addServer);
        } catch (Exception error) {
            items.add(UItem.asShadow("服务器配置损坏，未覆盖已有账号。"));
        }
    }

    private void addAccount(SafeLinkServer server) {
        try {
            int count = 0;
            int slot = -1;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) count++;
                else if (slot == -1) slot = a;
            }
            if (slot == -1 || count >= Math.min(UserConfig.MAX_ACCOUNT_COUNT, UserConfig.getMaxAccountCount())) {
                alert("已达到账号数量上限，请先退出一个账号。");
                return;
            }
            SafeLinkServers.bindForLogin(slot, server);
            presentFragment(new LoginActivity(slot));
        } catch (Exception error) { alert(error.getMessage()); }
    }

    private void addServer() {
        if (discovering) return;
        EditTextBoldCursor input = new EditTextBoldCursor(getContext());
        input.setSingleLine(true);
        input.setHint("IP 或 HTTPS 地址");
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        showDialog(new AlertDialog.Builder(getParentActivity()).setTitle("添加服务器").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("下一步", (dialog, which) -> {
                    final String address = input.getText().toString();
                    discovering = true;
                    list.adapter.update(true);
                    Utilities.globalQueue.postRunnable(() -> {
                        try {
                            SafeLinkServer server = SafeLinkServers.discover(address);
                            AndroidUtilities.runOnUIThread(() -> {
                                discovering = false;
                                if (getParentActivity() == null) return;
                                list.adapter.update(true);
                                showDialog(new AlertDialog.Builder(getParentActivity()).setTitle("确认服务器")
                                        .setMessage(server.name + "\n" + server.host + ":" + server.port + "\n\n公钥 SHA-256\n" + server.id)
                                        .setNegativeButton("取消", null).setPositiveButton("添加", (d, w) -> {
                                            try { SafeLinkServers.save(server); list.adapter.update(true); }
                                            catch (Exception e) { alert(e.getMessage()); }
                                        }).create());
                            });
                        } catch (Exception error) {
                            AndroidUtilities.runOnUIThread(() -> {
                                discovering = false;
                                if (getParentActivity() == null) return;
                                list.adapter.update(true);
                                alert("无法安全获取服务器配置，请检查 HTTPS 地址、证书和网络。");
                            });
                        }
                    });
                }).create());
    }

    private void alert(String text) {
        if (getParentActivity() != null) showDialog(new AlertDialog.Builder(getParentActivity()).setTitle("SafeLink")
                .setMessage(text).setPositiveButton("确定", null).create());
    }

    @Override public void onResume() {
        super.onResume();
        if (list != null) list.adapter.update(true);
    }
}
