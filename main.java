// ============================================================
// Jev 聊天助手 · WA (WAuxiliary) 版 v1.0
// 对方私聊发消息 → 本地秒判 意图/危险/情绪 → 可选 MiMo 给 3 条候选
// 结果以「插入系统消息」形式显示在聊天里；候选需手动确认才发送。
// 适配框架：微信 WA (WAuxiliary_Plugin)
// ============================================================

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** ==================== 默认配置 ==================== */
final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1";
final String DEFAULT_MODEL = "mimo-v2.6-flash";

final int DEFAULT_CONTEXT_ROUNDS = 0;
final int MAX_CONTEXT_ROUNDS = 30;
final int DEFAULT_PAD_CHARS = 24;
final String PAD_ANCHOR = String.valueOf((char) 160);
final String DIVIDER_CHAR = "─";

// 配色
final int BG_PANEL = Color.parseColor("#2A2A2A");
final int BG_CARD = Color.parseColor("#3A3A3A");
final int BG_INPUT = Color.parseColor("#404040");
final int TEXT_MAIN = Color.parseColor("#FFFFFF");
final int TEXT_SUB = Color.parseColor("#AAAAAA");
final int TEXT_HINT = Color.parseColor("#666666");
final int ACCENT_BLUE = Color.parseColor("#4A9EFF");
final int ACCENT_GREEN = Color.parseColor("#4AFF9E");
final int DIVIDER = Color.parseColor("#444444");

ExecutorService analyzePool = Executors.newCachedThreadPool();
java.util.concurrent.ScheduledExecutorService debouncePool = Executors.newSingleThreadScheduledExecutor();
java.util.concurrent.ConcurrentHashMap pendingLlms = new java.util.concurrent.ConcurrentHashMap();
String lastClipText = "";

/** ==================== 生命周期 ==================== */
void onLoad() { }

void onUnload() {
    try { analyzePool.shutdownNow(); } catch (Throwable e) { log("onUnload: " + e); }
}

/** ==================== 配置读写 ==================== */
String getApiKey()      { return getString("api_key", "").trim(); }
String getBaseUrl()     { String s = getString("base_url", ""); return isEmpty(s) ? DEFAULT_BASE_URL : s.trim(); }
String getModel()       { String s = getString("model", ""); return isEmpty(s) ? DEFAULT_MODEL : s.trim(); }
boolean getLlm()        { return getBoolean("llm_enabled", false); }
String getRelation()    { String s = getString("relation", ""); return isEmpty(s) ? "对方是我的关系亲密的对象" : s; }
String getActiveTalker(){ return getString("active_talker", "").trim(); }
void setActiveTalker(String t) { putString("active_talker", t == null ? "" : t); }
boolean isAutoAnalyze() { return getBoolean("auto_analyze", true); }
boolean isRevokeFirst() { return getBoolean("revoke_first", true); }
boolean isManualConfirm() { return getBoolean("manual_confirm", false); }
boolean isStrategyMode() { return getBoolean("strategy_mode", false); }
boolean isNoGray() { return getBoolean("no_gray", false); }
int getContextRounds() {
    int n = getInt("context_rounds", DEFAULT_CONTEXT_ROUNDS);
    return Math.max(0, Math.min(MAX_CONTEXT_ROUNDS, n));
}

/** ==================== 消息回调 ==================== */
void onHandleMsg(Object msgInfoBean) {
    try {
        if (msgInfoBean == null) return;
        if (!getBoolean(msgInfoBean, "isText")) return;
        if (getBoolean(msgInfoBean, "isSend")) return;
        String talker = getString(msgInfoBean, "getTalker");
        String content = getString(msgInfoBean, "getContent");
        if (isEmpty(talker) || isEmpty(content)) return;
        content = content.trim();
        if (isEmpty(content)) return;
        if (!talker.equals(getActiveTalker())) return;
        // 群聊不分析
        if (getBoolean(msgInfoBean, "isGroupChat")) return;

        // 1) 第一轮本地秒判：作用域开启就立即执行
        long firstId = -1;
        String quick = localQuick(talker, content);
        if (!isEmpty(quick)) {
            if (isNoGray()) {
                // 不单独弹，等确认弹窗合并
            } else {
                try { firstId = insertSystemMsg(talker, quick, System.currentTimeMillis()); }
                catch (Throwable ie) { log("insert err: " + ie); }
            }
        }

        // 2) 第二轮大模型：可选
        boolean useAi = getLlm() && !isEmpty(getApiKey());
        if (!useAi) {
            if (isNoGray() && !isEmpty(quick)) showResultDialog("本地判断", quick);
            return;
        }
        scheduleLlm(talker, firstId, content, quick);
    } catch (Throwable e) {
        log("onHandleMsg: " + e);
    }
}

void scheduleLlm(final String talker, final long firstId, final String curContent, final String quickText) {
    final boolean fRevoke = isRevokeFirst();
    runLlm(talker, firstId, fRevoke, curContent, quickText);
}

void runLlm(final String talker, final long firstId, final boolean fRevoke, final String curContent, final String quickText) {
    try {
        if (isManualConfirm()) {
            final android.app.Activity act;
            try { act = getTopActivity(); } catch (Throwable te) { submitLlm(talker, firstId, fRevoke); return; }
            if (act == null) { toast("前台无 Activity，跳过确认"); return; }
            act.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        StringBuilder msg = new StringBuilder();
                        msg.append("对方说：\n").append(curContent == null ? "" : (curContent.length() > 200 ? curContent.substring(0,200) + "…" : curContent));
                        if (!isEmpty(quickText)) {
                            msg.append("\n\n本地判断：\n").append(quickText);
                        }
                        new android.app.AlertDialog.Builder(act)
                            .setTitle("Jev：是否调用大模型分析？")
                            .setMessage(msg.toString())
                            .setPositiveButton("分析", new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface d, int w) { submitLlm(talker, firstId, fRevoke); }
                            })
                            .setNegativeButton("忽略", null)
                            .show();
                    } catch (Throwable de) { log("dlg: " + de); }
                }
            });
            return;
        }
        submitLlm(talker, firstId, fRevoke);
    } catch (Throwable e) { log("runLlm: " + e); }
}

void submitLlm(final String talker, final long firstId, final boolean fRevoke) {
    toast("大模型分析中…");
    log("llm start (debounced)");
    analyzePool.submit(new Runnable() {
        public void run() {
            long t0 = System.currentTimeMillis();
            try {
                String content = joinRecentIncoming(talker, getContextRounds());
                String ai = callMimo(talker, content);
                long dt = System.currentTimeMillis() - t0;
                log("llm cost " + dt + "ms, result=" + (ai == null ? "null" : ai.length() + " chars"));
                if (!isEmpty(ai)) {
                    if (!isNoGray()) {
                        if (fRevoke && firstId > 0) { try { revokeMsg(firstId); } catch (Throwable ignore) {} }
                        insertSystemMsg(talker, ai, System.currentTimeMillis());
                    } else {
                        showResultDialog("大模型建议", ai);
                    }
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) hostContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev", isEmpty(lastClipText) ? ai : lastClipText));
                        toast("已复制3条回复到剪贴板");
                    } catch (Throwable ce) { log("clip: " + ce); }
                } else {
                    if (isNoGray()) showResultDialog("错误", "大模型无返回，详见日志");
                    else insertSystemMsg(talker, "[Jev] 大模型无返回，详见日志", System.currentTimeMillis());
                    toast("大模型无返回，请看日志");
                }
            } catch (Throwable e) { log("MiMo: " + e); }
        }
    });
}

/** 弹窗显示结果 */
void showResultDialog(String title, String body) {
    try {
        final android.app.Activity act = getTopActivity();
        if (act == null) return;
        final String fTitle = title; final String fBody = body;
        act.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    new android.app.AlertDialog.Builder(act)
                        .setTitle("Jev：" + fTitle)
                        .setMessage(fBody)
                        .setPositiveButton("好", null)
                        .show();
                } catch (Throwable e) { log("showDlg: " + e); }
            }
        });
    } catch (Throwable e) { log("showDlg2: " + e); }
}

boolean isOurSystemMsg(String txt) {
    if (isEmpty(txt)) return true;
    if (txt.indexOf("危险") >= 0 && txt.indexOf("/9") >= 0) return true;
    if (txt.indexOf("想找你") >= 0 || txt.indexOf("对方发了") >= 0) return true;
    if (txt.startsWith("[Jev]")) return true;
    return false;
}

/** 读最近 N 条 incoming 文本拼成一段（WA: queryHistoryMsg 4 参数），过滤我们自己的系统消息 */
String joinRecentIncoming(String talker, int n) {
    try {
        int limit = Math.max(n, 1) + 5;
        java.util.List tail = queryHistoryMsg(talker, System.currentTimeMillis(), false, limit);
        if (tail == null || tail.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int added = 0;
        int want = Math.max(n, 1);
        for (int i = tail.size() - 1; i >= 0 && added < want; i--) {
            Object m = tail.get(i);
            boolean isSend = getBoolean(m, "isSend");
            if (isSend) continue;
            String txt = getString(m, "getContent");
            if (isEmpty(txt)) continue;
            if (isOurSystemMsg(txt)) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(txt);
            added++;
        }
        return sb.toString();
    } catch (Throwable e) { log("joinRecent: " + e); return ""; }
}

/** /jev 指令打开配置 */
boolean onClickSendBtn(String text) {
    try {
        if (text == null) return false;
        String cmd = text.trim();
        if ("/jev".equalsIgnoreCase(cmd) || "/jev配置".equals(cmd) || "/jev设置".equals(cmd)) {
            showMainDialog();
            return true;
        }
    } catch (Throwable e) { log("onClickSendBtn: " + e); }
    return false;
}

/** ==================== 本地快速判断 ==================== */
String localQuick(String talker, String content) {
    try {
        JSONArray hist = recentOtherTexts(talker);
        int happy = 0, sad = 0, angry = 0;
        // 当前消息权重高
        int[] cur = score(content);
        happy += cur[0] * 3; sad += cur[1] * 3; angry += cur[2] * 3;
        for (int i = 0; i < hist.length(); i++) {
            int[] s = score(String.valueOf(hist.get(i)));
            happy += s[0]; sad += s[1]; angry += s[2];
        }
        int sum = happy + sad + angry;
        String emotion = "平静"; int pct = 0;
        if (sum > 0) {
            if (angry >= 85 && angry >= happy && angry >= sad) { emotion = "生气"; pct = Math.min(99, angry * 100 / sum); }
            else if (sad >= 70 && sad >= happy && sad >= angry) { emotion = "难过"; pct = Math.min(99, sad * 100 / sum); }
            else if (happy >= sad && happy >= angry) { emotion = "开心"; pct = happy * 100 / sum; }
            else if (sad >= angry) { emotion = "难过"; pct = sad * 100 / sum; }
            else { emotion = "生气"; pct = angry * 100 / sum; }
        }
        String intent = intentOf(content);
        int danger = 3;
        if (intent.indexOf("情绪") >= 0 || intent.indexOf("发泄") >= 0) danger = 6;
        if (emotion.equals("生气") && pct > 40) danger = 7;
        if (emotion.equals("难过") && pct > 50) danger = 5;
        if (content.length() <= 6) danger = Math.min(danger, 4);

        StringBuilder sb = new StringBuilder();
        sb.append(padLine(intent + " · 危险" + danger + "/9 · " + emotion + " " + pct + "%"));
        sb.append("\n ");
        return sb.toString();
    } catch (Throwable e) {
        log("localQuick: " + e);
        return null;
    }
}

/** 返回 [开心,难过,生气] 加权分 */
int[] score(String t) {
    int happy = 0, sad = 0, angry = 0;
    if (t == null) return new int[]{0,0,0};
    if (t.indexOf("哈哈")>=0 || t.indexOf("嘻嘻")>=0 || t.indexOf("嘿嘿")>=0 || t.indexOf("笑死")>=0
        || t.indexOf("太好了")>=0 || t.indexOf("开心")>=0 || t.indexOf("高兴")>=0 || t.indexOf("爱你")>=0
        || t.indexOf("喜欢你")>=0 || t.indexOf("想你")>=0 || t.indexOf("棒")>=0 || t.indexOf("赞")>=0) happy += 60;
    if (t.indexOf("呜呜")>=0 || t.indexOf("哭")>=0 || t.indexOf("难过")>=0 || t.indexOf("伤心")>=0
        || t.indexOf("难受")>=0 || t.indexOf("好累")>=0 || t.indexOf("心累")>=0 || t.indexOf("想哭")>=0
        || t.indexOf("emo")>=0 || t.indexOf("不开心")>=0 || t.indexOf("委屈")>=0 || t.indexOf("失望")>=0
        || t.indexOf("沮丧")>=0 || t.indexOf("疼")>=0 || t.indexOf("姨妈")>=0 || t.indexOf("不舒服")>=0
        || t.indexOf("生病")>=0 || t.indexOf("发烧")>=0) sad += 70;
    if (t.indexOf("气死")>=0 || t.indexOf("生气")>=0 || t.indexOf("好气")>=0 || t.indexOf("气人")>=0
        || t.indexOf("他妈")>=0 || t.indexOf("滚")>=0 || t.indexOf("够了")>=0 || t.indexOf("闭嘴")>=0
        || t.indexOf("讨厌")>=0 || t.indexOf("无语")>=0 || t.indexOf("烦")>=0
        || t.indexOf("活该")>=0 || t.indexOf("单身")>=0 || t.indexOf("去吧")>=0 || t.indexOf("一辈子")>=0
        || t.indexOf("别联系")>=0 || t.indexOf("拉黑")>=0 || t.indexOf("分手")>=0 || t.indexOf("再也不")>=0
        || t.indexOf("行吧")>=0 || t.indexOf("呵呵")>=0 || t.indexOf("随便你")>=0 || t.indexOf("无所谓")>=0
        || t.indexOf("！！")>=0 || t.indexOf("!!")>=0) angry += 90;
    return new int[]{happy, sad, angry};
}

String intentOf(String t) {
    if (t == null) return "闲聊";
    if (t.indexOf("活该")>=0 || t.indexOf("去吧")>=0 || t.indexOf("别联系")>=0 || t.indexOf("分手")>=0
        || t.indexOf("！！")>=0 || t.indexOf("气死")>=0 || t.indexOf("无语")>=0 || t.indexOf("讨厌")>=0) return "生气发泄";
    if (t.indexOf("好气")>=0 || t.indexOf("生气")>=0) return "发泄情绪";
    if (t.indexOf("难过")>=0 || t.indexOf("想哭")>=0 || t.indexOf("难受")>=0 || t.indexOf("委屈")>=0 || t.indexOf("不开心")>=0 || t.indexOf("emo")>=0 || t.indexOf("疼")>=0 || t.indexOf("姨妈")>=0 || t.indexOf("不舒服")>=0 || t.indexOf("累")>=0) return "求安慰";
    if (t.indexOf("在吗")>=0 || t.indexOf("在干嘛")>=0 || t.indexOf("在不")>=0 || t.indexOf("睡了吗")>=0 || t.indexOf("想你")>=0 || t.indexOf("喜欢你")>=0) return "想找你";
    if (t.indexOf("为什么")>=0 || t.indexOf("怎么回事")>=0 || t.indexOf("怎么办")>=0 || t.indexOf("怎么还")>=0) return "求关注";
    if (t.indexOf("好吗")>=0 || t.indexOf("可以吗")>=0 || t.indexOf("要不")>=0) return "约你";
    if (t.indexOf("又")>=0 || t.indexOf("每次都")>=0) return "抱怨";
    return "闲聊";
}

/** 取最近对方文本（不含当前） */
JSONArray recentOtherTexts(String talker) {
    JSONArray arr = new JSONArray();
    try {
        int limit = getContextRounds();
        if (limit <= 0) return arr;
        List list = queryHistoryMsg(talker, System.currentTimeMillis(), false, limit + 1);
        if (list == null) return arr;
        for (int i = 0; i < list.size(); i++) {
            Object bean = list.get(i);
            if (bean == null) continue;
            if (getBoolean(bean, "isSend")) continue;
            if (!getBoolean(bean, "isText")) continue;
            String c = getString(bean, "getContent");
            if (!isEmpty(c)) arr.put(c.trim());
        }
    } catch (Throwable e) { log("hist: " + e); }
    return arr;
}

/** ==================== MiMo 第二轮 ==================== */
String callMimo(String talker, String content) {
    try {
        String rel = getRelation();
        String sys;
        if (isStrategyMode()) {
            sys =
                "你是一个恋爱聊天军师。看完对方的话，先判断当前对话阶段，再选对应策略出回复。\n" +
                "阶段：承接(对方在说情绪/事情，需要被接住) / 降压(对方在施压或生气) / 调侃(关系轻松，可以开玩笑) / 轻推(暧昧期，主动推进) / 约见(聊得不错，该约线下) / 澄清(有误会或信号模糊) / 收线(聊得够了，主动收尾)。\n" +
                "只输出 JSON，不要解释：\n" +
                "{\"intent\":\"他在干嘛\",\"stage\":\"承接/降压/调侃/轻推/约见/澄清/收线\",\"danger\":1,\"emotion\":\"开心/难过/生气/平静\",\"pct\":0,\"action\":\"一句战术建议\",\"replies\":[\"回复1\",\"回复2\",\"回复3\"]}\n" +
                "要求：回复短而口语，像朋友；三条风格不同；别写长句。";
        } else {
            sys =
                "帮我看对方什么意思，给三条能直接发的回复。只输出 JSON：\n" +
                "{\"intent\":\"\",\"danger\":1,\"emotion\":\"开心/难过/生气/平静\",\"pct\":0,\"action\":\"\",\"replies\":[\"\",\"\",\"\"]}\n" +
                "回复短，口语，像朋友。danger 1=日常，9=借钱/推销/吵架/诈骗。";
        }
        if (!isEmpty(rel)) sys += " 关系:" + rel;
        String typeHint = relationTypeHint();
        if (!isEmpty(typeHint)) sys += " " + typeHint;

        JSONArray messages = new JSONArray();
        JSONObject sm = new JSONObject(); sm.put("role","system"); sm.put("content",sys); messages.put(sm);
        JSONArray hist = recentOtherTexts(talker);
        for (int i = 0; i < hist.length(); i++) {
            JSONObject m = new JSONObject(); m.put("role","user"); m.put("content", String.valueOf(hist.get(i))); messages.put(m);
        }
        JSONObject cur = new JSONObject(); cur.put("role","user"); cur.put("content", content); messages.put(cur);

        JSONObject body = new JSONObject();
        body.put("model", getModel());
        body.put("messages", messages);
        body.put("temperature", 0.6);
        body.put("max_tokens", 400);

        String resp = postJson(body);
        if (isEmpty(resp)) return null;
        JSONObject json = new JSONObject(resp);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return null;
        String text = choices.getJSONObject(0).optJSONObject("message").optString("content","");
        String s = text.trim();
        int a = s.indexOf("{"), b = s.lastIndexOf("}");
        if (a >= 0 && b > a) s = s.substring(a, b + 1);
        JSONObject ai = new JSONObject(s);

        // 剪贴板只放三条回复，去掉编号，中间空行
        lastClipText = "";
        JSONArray replies0 = ai.optJSONArray("replies");
        if (replies0 != null) {
            StringBuilder cb = new StringBuilder();
            for (int i = 0; i < replies0.length(); i++) {
                if (i > 0) cb.append("\n\n");
                cb.append(String.valueOf(replies0.opt(i)));
            }
            lastClipText = cb.toString();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(padLine("危险 " + ai.optInt("danger",3) + "/9 · " + ai.optString("emotion","") + " " + ai.optInt("pct",0) + "%")).append("\n");
        sb.append(padLine(clip(ai.optString("intent",""), 18))).append("\n");
        sb.append(padLine("建议 " + clip(ai.optString("action",""), 22)));
        return sb.toString();
    } catch (Throwable e) {
        log("callMimo: " + e);
        return null;
    }
}

String postJson(JSONObject body) {
    HttpURLConnection conn = null;
    try {
        String url = getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + getApiKey());
        conn.setDoOutput(true);
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(30000);
        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.close();
        int code = conn.getResponseCode();
        if (code != 200) { log("MiMo HTTP " + code); return null; }
        InputStream is = conn.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    } catch (Throwable e) {
        log("postJson: " + e);
        return null;
    } finally {
        if (conn != null) try { conn.disconnect(); } catch (Throwable ignore) {}
    }
}

/** ==================== 排版 ==================== */
int sysPadWidth() {
    try {
        int cfg = getInt("pad_chars", 0);
        if (cfg >= 8 && cfg <= 40) return cfg;
    } catch (Throwable e) {}
    try {
        Activity act = getTopActivity();
        if (act != null) {
            android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
            float charPx = 12f * Math.max(dm.density, dm.scaledDensity);
            if (charPx > 0) {
                int n = (int)(dm.widthPixels * 0.68f / charPx);
                if (n < 8) n = 8; if (n > 40) n = 40;
                return n;
            }
        }
    } catch (Throwable e) {}
    return DEFAULT_PAD_CHARS;
}
double visualWidth(String s) {
    double w = 0;
    int n = s == null ? 0 : s.length();
    for (int i = 0; i < n; i++) { if (s.charAt(i) < 128) w += 0.5; else w += 1; }
    return w;
}
String padLine(String line) {
    try {
        if (line == null) return "";
        int target = sysPadWidth();
        double w = visualWidth(line);
        if (w >= target) return line;
        StringBuilder sb = new StringBuilder(line);
        double i = w;
        while (i < target - 1) { sb.append("　"); i += 1; }
        sb.append(PAD_ANCHOR);
        return sb.toString();
    } catch (Throwable e) { return line; }
}

/** ==================== 配置界面 ==================== */
void showMainDialog() {
    final Activity activity = getTopActivity();
    if (activity == null) { toast("请先打开微信聊天"); return; }
    final String talker = getTargetTalker();
    if (isEmpty(talker)) { toast("请先进入一个聊天会话"); return; }

    activity.runOnUiThread(new Runnable() {
        public void run() {
            try {
                AlertDialog dialog = new AlertDialog.Builder(activity).create();
                dialog.setCanceledOnTouchOutside(true);
                LinearLayout root = new LinearLayout(activity);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setBackgroundColor(Color.TRANSPARENT);
                root.setPadding(dp(16), dp(40), dp(16), dp(40));

                LinearLayout panel = new LinearLayout(activity);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setBackground(createPanelBg(activity));
                panel.setPadding(dp(18), dp(20), dp(18), dp(18));

                LinearLayout headerRow = new LinearLayout(activity);
                headerRow.setOrientation(LinearLayout.HORIZONTAL);
                TextView title = new TextView(activity);
                title.setText("Jev 聊天助手 (WA)");
                title.setTextSize(19); title.setTypeface(null, Typeface.BOLD); title.setTextColor(TEXT_MAIN);
                title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                headerRow.addView(title);
                TextView closeBtn = new TextView(activity);
                closeBtn.setText("✕"); closeBtn.setTextSize(17); closeBtn.setTextColor(TEXT_SUB);
                closeBtn.setPadding(dp(10), dp(4), dp(4), dp(4));
                closeBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dialog.dismiss(); } });
                headerRow.addView(closeBtn);
                panel.addView(headerRow);

                TextView sub = new TextView(activity);
                sub.setText("本地秒判 + 可选 MiMo 候选，手动确认才发送");
                sub.setTextSize(10); sub.setTextColor(TEXT_HINT); sub.setPadding(0, dp(6), 0, dp(14));
                panel.addView(sub);

                ScrollView scroll = new ScrollView(activity);
                scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
                LinearLayout content = new LinearLayout(activity);
                content.setOrientation(LinearLayout.VERTICAL);

                content.addView(createSectionTitle(activity, "作用域（开启后自动跑第一轮判断）"));
                content.addView(createScopeCard(activity, talker));

                content.addView(createSectionTitle(activity, "第二轮决策开关（更聪明版）"));
                final android.widget.ToggleButton masterToggle = new android.widget.ToggleButton(activity);
                masterToggle.setTextOn("已启用"); masterToggle.setTextOff("已关闭");
                masterToggle.setChecked(getLlm());
                content.addView(masterToggle);

                final LinearLayout llmPanel = new LinearLayout(activity);
                llmPanel.setOrientation(LinearLayout.VERTICAL);
                content.addView(llmPanel);
                llmPanel.addView(createToggleItem(activity, "大模型分析前手动确认（弹窗）", "manual_confirm", false));
                llmPanel.addView(createToggleItem(activity, "策略模式（更准但更慢）", "strategy_mode", false));
                llmPanel.addView(createToggleItem(activity, "撤回第一轮本地判断", "revoke_first", true));
                llmPanel.addView(createToggleItem(activity, "关闭聊天灰字（结果只走弹窗）", "no_gray", false));

                llmPanel.addView(createSectionTitle(activity, "大模型配置（任意 OpenAI 兼容）"));
                final EditText keyInput = createInputCard(activity, llmPanel, "API 密钥（sk- 开头）", getApiKey(), false);
                final EditText urlInput = createInputCard(activity, llmPanel, "API 地址（/v1 结尾，默认 MiMo）", getBaseUrl(), false);
                final EditText modelInput = createInputCard(activity, llmPanel, "模型名（如 deepseek-chat / qwen-plus）", getModel(), false);
                final android.widget.Spinner relTypeSpinner = createRelTypeSpinner(activity, llmPanel);
                final EditText relInput = createInputCard(activity, llmPanel, "关系补充说明（可空，如：认识三个月）", getRelation(), false);
                final EditText ctxInput = createInputCard(activity, llmPanel, "上下文轮数 0-" + MAX_CONTEXT_ROUNDS, String.valueOf(getContextRounds()), true);
                final EditText padInput = createInputCard(activity, llmPanel, "排版宽度 0=自动", String.valueOf(getInt("pad_chars", 0)), true);

                Runnable syncPanel = new Runnable() {
                    public void run() { llmPanel.setVisibility(masterToggle.isChecked() ? View.VISIBLE : View.GONE); }
                };
                masterToggle.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { syncPanel.run(); }
                });
                syncPanel.run();

                scroll.addView(content);
                panel.addView(scroll);

                LinearLayout btnRow = new LinearLayout(activity);
                btnRow.setOrientation(LinearLayout.HORIZONTAL); btnRow.setGravity(Gravity.END);
                TextView saveBtn = new TextView(activity);
                saveBtn.setText("保存"); saveBtn.setTextSize(13); saveBtn.setTextColor(TEXT_MAIN);
                saveBtn.setBackground(createChipBg(true)); saveBtn.setPadding(dp(20), dp(9), dp(20), dp(9));
                saveBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        putBoolean("llm_enabled", masterToggle.isChecked());
                        putString("api_key", keyInput.getText().toString().trim());
                        putString("base_url", urlInput.getText().toString().trim());
                        putString("model", modelInput.getText().toString().trim());
                        putString("relation_type", String.valueOf(relTypeSpinner.getSelectedItemPosition()));
                        putString("relation", relInput.getText().toString().trim());
                        int rounds = DEFAULT_CONTEXT_ROUNDS;
                        try { rounds = Integer.parseInt(ctxInput.getText().toString().trim()); } catch (Throwable ignore) {}
                        putInt("context_rounds", Math.max(0, Math.min(MAX_CONTEXT_ROUNDS, rounds)));
                        int pad = 0;
                        try { pad = Integer.parseInt(padInput.getText().toString().trim()); } catch (Throwable ignore) {}
                        putInt("pad_chars", Math.max(0, Math.min(40, pad)));
                        toast("已保存");
                    }
                });
                btnRow.addView(saveBtn);
                TextView cancelBtn = new TextView(activity);
                cancelBtn.setText("关闭"); cancelBtn.setTextSize(13); cancelBtn.setTextColor(TEXT_SUB);
                cancelBtn.setBackground(createChipBg(false)); cancelBtn.setPadding(dp(20), dp(9), dp(20), dp(9));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.leftMargin = dp(10); cancelBtn.setLayoutParams(cp);
                cancelBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dialog.dismiss(); } });
                btnRow.addView(cancelBtn);
                panel.addView(btnRow);

                root.addView(panel);
                dialog.show();
                Window w = dialog.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    w.setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT);
                    w.setContentView(root);
                    w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                    w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
                }
            } catch (Throwable e) {
                log("dialog: " + e);
                toast("配置页打开失败: " + e.getMessage());
            }
        }
    });
}

View createSectionTitle(Context ctx, String text) {
    TextView tv = new TextView(ctx);
    tv.setText(text); tv.setTextSize(12); tv.setTextColor(TEXT_SUB); tv.setPadding(0, dp(12), 0, dp(6));
    return tv;
}

String[] REL_TYPE_LABELS = {"不指定", "男女朋友（亲密）", "想追的人（暧昧期）", "普通朋友", "同事（工作关系）", "客户/陌生人（客气）"};

android.widget.Spinner createRelTypeSpinner(Context ctx, LinearLayout parent) {
    LinearLayout card = new LinearLayout(ctx);
    card.setOrientation(LinearLayout.VERTICAL); card.setBackground(createCardBg(ctx));
    card.setPadding(dp(14), dp(12), dp(14), dp(12));
    TextView label = new TextView(ctx);
    label.setText("聊天对象类型"); label.setTextSize(13); label.setTextColor(TEXT_HINT);
    card.addView(label);
    android.widget.Spinner sp = new android.widget.Spinner(ctx);
    android.widget.ArrayAdapter adapter = new android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_item, REL_TYPE_LABELS) {
        public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
            android.view.View v = super.getView(position, convertView, parent);
            if (v instanceof TextView) ((TextView) v).setTextColor(TEXT_MAIN);
            return v;
        }
        public android.view.View getDropDownView(int position, android.view.View convertView, android.view.ViewGroup parent) {
            android.view.View v = super.getDropDownView(position, convertView, parent);
            if (v instanceof TextView) ((TextView) v).setTextColor(TEXT_MAIN);
            return v;
        }
    };
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    sp.setAdapter(adapter);
    int sel = 0;
    try { sel = getInt("relation_type", 0); } catch (Throwable ignore) {}
    if (sel >= 0 && sel < REL_TYPE_LABELS.length) sp.setSelection(sel);
    card.addView(sp);
    parent.addView(card);
    return sp;
}

String relationTypeHint() {
    int t = 0;
    try { t = getInt("relation_type", 0); } catch (Throwable ignore) {}
    switch (t) {
        case 1: return "对方是我的男女朋友：可以亲密撒娇，但也要接住情绪，别太敷衍。";
        case 2: return "对方是我正在追的人：保持分寸感，别过度热情，多展示价值，少追问。";
        case 3: return "对方是普通朋友：轻松随意，别越界。";
        case 4: return "对方是同事：专业客气，别聊私事。";
        case 5: return "对方是客户/陌生人：礼貌周到，保持距离。";
        default: return "";
    }
}

EditText createInputCard(Context ctx, LinearLayout parent, String hint, String value, boolean numOnly) {
    LinearLayout card = new LinearLayout(ctx);
    card.setOrientation(LinearLayout.VERTICAL); card.setBackground(createCardBg(ctx));
    card.setPadding(dp(14), dp(12), dp(14), dp(12));
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    cp.bottomMargin = dp(10); card.setLayoutParams(cp);
    EditText input = new EditText(ctx);
    input.setText(value); input.setHint(hint); input.setTextSize(13);
    input.setTextColor(TEXT_MAIN); input.setHintTextColor(TEXT_HINT);
    input.setBackground(createInputBg(ctx)); input.setPadding(dp(14), dp(11), dp(14), dp(11));
    input.setInputType(numOnly ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
    input.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    card.addView(input);
    TextView lbl = new TextView(ctx);
    lbl.setText(hint); lbl.setTextSize(10); lbl.setTextColor(TEXT_HINT);
    lbl.setPadding(0, dp(6), 0, 0);
    card.addView(lbl);
    parent.addView(card);
    return input;
}
View createScopeCard(final Context ctx, final String talker) {
    LinearLayout card = new LinearLayout(ctx);
    card.setOrientation(LinearLayout.VERTICAL); card.setBackground(createCardBg(ctx));
    card.setPadding(dp(14), dp(12), dp(14), dp(12));
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    cp.bottomMargin = dp(10); card.setLayoutParams(cp);
    LinearLayout row = new LinearLayout(ctx);
    row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
    final boolean en = talker.equals(getActiveTalker());
    final TextView st = new TextView(ctx);
    st.setText(en ? "本会话：已开启" : "本会话：未开启");
    st.setTextSize(14); st.setTextColor(en ? ACCENT_GREEN : TEXT_MAIN);
    st.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    row.addView(st);
    final TextView tg = new TextView(ctx);
    tg.setText(en ? "关闭" : "开启"); tg.setTextSize(12); tg.setTextColor(TEXT_MAIN);
    tg.setBackground(createChipBg(en)); tg.setPadding(dp(14), dp(5), dp(14), dp(5));
    row.addView(tg); card.addView(row);
    card.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            if (talker.equals(getActiveTalker())) setActiveTalker(""); else setActiveTalker(talker);
            boolean after = talker.equals(getActiveTalker());
            st.setText(after ? "本会话：已开启" : "本会话：未开启");
            st.setTextColor(after ? ACCENT_GREEN : TEXT_MAIN);
            tg.setText(after ? "关闭" : "开启");
            tg.setBackground(createChipBg(after));
            toast(after ? "已开启本会话" : "已关闭");
        }
    });
    return card;
}
View createToggleItem(final Context ctx, String label, final String key, boolean def) {
    LinearLayout item = new LinearLayout(ctx);
    item.setOrientation(LinearLayout.HORIZONTAL); item.setGravity(Gravity.CENTER_VERTICAL);
    item.setBackground(createCardBg(ctx)); item.setPadding(dp(14), dp(12), dp(14), dp(12));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.bottomMargin = dp(10); item.setLayoutParams(lp);
    TextView tv = new TextView(ctx); tv.setText(label); tv.setTextSize(14); tv.setTextColor(TEXT_MAIN);
    tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    item.addView(tv);
    final boolean checked = getBoolean(key, def);
    final TextView tg = new TextView(ctx);
    tg.setText(checked ? "开" : "关"); tg.setTextSize(12); tg.setTextColor(TEXT_MAIN);
    tg.setBackground(createChipBg(checked)); tg.setPadding(dp(14), dp(5), dp(14), dp(5));
    item.addView(tg);
    final boolean[] st = new boolean[]{checked};
    item.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            st[0] = !st[0]; putBoolean(key, st[0]);
            tg.setText(st[0] ? "开" : "关"); tg.setBackground(createChipBg(st[0]));
        }
    });
    return item;
}

/** ==================== 工具 ==================== */
int dp(float value) {
    try { return (int)(value * hostContext.getResources().getDisplayMetrics().density + 0.5f); }
    catch (Throwable e) { return (int) value; }
}
GradientDrawable createPanelBg(Context ctx) { GradientDrawable g = new GradientDrawable(); g.setColor(BG_PANEL); g.setCornerRadius(dp(18)); return g; }
GradientDrawable createCardBg(Context ctx) { GradientDrawable g = new GradientDrawable(); g.setColor(BG_CARD); g.setCornerRadius(dp(12)); return g; }
GradientDrawable createInputBg(Context ctx) { GradientDrawable g = new GradientDrawable(); g.setColor(BG_INPUT); g.setCornerRadius(dp(10)); return g; }
GradientDrawable createChipBg(boolean on) {
    GradientDrawable g = new GradientDrawable();
    if (on) { g.setColor(Color.parseColor("#1A3A2A")); g.setStroke(dp(1), ACCENT_GREEN); }
    else { g.setColor(BG_INPUT); g.setStroke(dp(1), DIVIDER); }
    g.setCornerRadius(dp(20)); return g;
}
String clip(String s, int m) { return (s == null || s.length() <= m) ? s : s.substring(0, m) + "…"; }
boolean isEmpty(String s) { return s == null || s.trim().length() == 0; }

Object callMethod(Object obj, String mn) {
    try {
        Class c = obj.getClass();
        while (c != null) {
            Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++)
                if (ms[i].getName().equals(mn) && ms[i].getParameterCount() == 0) {
                    ms[i].setAccessible(true); return ms[i].invoke(obj);
                }
            c = c.getSuperclass();
        }
    } catch (Throwable e) {}
    return null;
}
boolean getBoolean(Object o, String mn) { Object v = callMethod(o, mn); return v instanceof Boolean ? ((Boolean) v).booleanValue() : false; }
String getString(Object o, String mn) { Object v = callMethod(o, mn); return v == null ? "" : String.valueOf(v); }
