package cn.office.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    public record Reply(String text, String confirmationId, String downloadUrl) {}
    public record StreamChunk(String type, String text, String confirmationId, String downloadUrl) {}
    private record PendingApproval(String user, long requestId, Instant created) {}
    private final Map<String, PendingApproval> approvals = new ConcurrentHashMap<>();
    private final LedgerService ledger;
    private final ObjectMapper json;
    private final String apiKey = System.getenv("MODEL_API_KEY");
    private final String modelName = System.getenv().getOrDefault("MODEL_NAME", "deepseek-chat");
    private final String baseUrl = System.getenv().getOrDefault("MODEL_BASE_URL", "https://api.deepseek.com");

    public ChatService(LedgerService ledger, ObjectMapper json) { this.ledger = ledger; this.json = json; }

    public Reply respond(String user, String message, String confirmationId) {
        ledger.user(user);
        if (confirmationId != null && !confirmationId.isBlank()) return confirm(user, confirmationId);
        if (message == null || message.isBlank()) throw new BusinessException("请输入消息");
        if (apiKey == null || apiKey.isBlank()) return localReply(user, message.trim());
        Tools tools = new Tools(user);
        try (ReActAgent agent = agent(user, tools)) {
            Msg input = Msg.builder().role(MsgRole.USER).textContent(message).build();
            Msg result = agent.call(List.of(input)).block(Duration.ofSeconds(25));
            if (result == null) throw new BusinessException("模型暂时没有返回，请重试");
            return new Reply(result.getTextContent(), tools.confirmationId, tools.downloadUrl);
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException("模型服务不可用或超时，请使用页面操作"); }
    }

    public void stream(String user, String message, String confirmationId, Consumer<StreamChunk> send) {
        ledger.user(user);
        if (confirmationId != null && !confirmationId.isBlank()) {
            send.accept(done(confirm(user, confirmationId)));
            return;
        }
        if (message == null || message.isBlank()) throw new BusinessException("请输入消息");
        if (apiKey == null || apiKey.isBlank()) {
            send.accept(done(localReply(user, message.trim())));
            return;
        }
        Tools tools = new Tools(user);
        StringBuilder partial = new StringBuilder();
        final String[] finalText = {null};
        try (ReActAgent agent = agent(user, tools)) {
            Msg input = Msg.builder().role(MsgRole.USER).textContent(message).build();
            // 只转发可见文本，不把思考块、工具参数或工具结果送到页面。
            agent.streamEvents(input).doOnNext(event -> {
                if (event instanceof TextBlockDeltaEvent deltaEvent) {
                    String delta = deltaEvent.getDelta();
                    if (delta != null && !delta.isEmpty()) {
                        partial.append(delta);
                        send.accept(new StreamChunk("delta", delta, null, null));
                    }
                } else if (event instanceof AgentResultEvent resultEvent && resultEvent.getResult() != null) {
                    finalText[0] = resultEvent.getResult().getTextContent();
                }
            }).blockLast(Duration.ofSeconds(25));
            String answer = finalText[0] != null ? finalText[0] : partial.toString();
            if (answer.isBlank()) throw new BusinessException("模型暂时没有返回，请重试");
            send.accept(done(new Reply(answer, tools.confirmationId, tools.downloadUrl)));
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException("模型服务不可用或超时，请使用页面操作"); }
    }

    private StreamChunk done(Reply reply) {
        return new StreamChunk("done", reply.text(), reply.confirmationId(), reply.downloadUrl());
    }

    private ReActAgent agent(String user, Tools tools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        return ReActAgent.builder().name("office_assistant")
            .sysPrompt("你是办公用品台账助手。当前用户是" + user + "。必须使用工具查询事实，禁止自行计算库存。" +
                "创建领用时先查物料编码，再调用 createIssue，参数格式为 M001:5,M002:10。" +
                "审批只调用 prepareApproval，展示详情并等待用户在页面确认；绝不能说已审批。" +
                "用户要求导出台账时调用 exportLedger。回答简短，不透露工具实现。")
            .model(OpenAIChatModel.builder().apiKey(apiKey).modelName(modelName).baseUrl(baseUrl).build())
            .toolkit(toolkit).maxIters(5).build();
    }

    private Reply confirm(String user, String token) {
        PendingApproval pending = approvals.get(token);
        if (pending == null || pending.created().isBefore(Instant.now().minusSeconds(600)))
            throw new BusinessException("确认已过期，请重新查看申请");
        if (!pending.user().equals(user)) throw new BusinessException("当前用户与待确认操作不一致");
        ledger.approve(pending.requestId(), user);
        approvals.remove(token);
        return new Reply("领用申请 LY-" + pending.requestId() + " 已审批通过，库存已更新。", null, null);
    }

    private Reply prepare(String user, long id) {
        if (!ledger.user(user).role().equals("审批人")) throw new BusinessException("只有审批人可以审批");
        Map<String, Object> details = ledger.requestDetails(id);
        if (!"待审批".equals(details.get("STATUS"))) throw new BusinessException("申请已处理");
        if (user.equals(details.get("APPLICANT"))) throw new BusinessException("不能审批自己提交的申请");
        String token = UUID.randomUUID().toString();
        approvals.put(token, new PendingApproval(user, id, Instant.now()));
        StringBuilder text = new StringBuilder("申请 LY-").append(id).append("，申请人：")
            .append(details.get("APPLICANT")).append("。\n");
        for (Map<String, Object> item : (List<Map<String, Object>>) details.get("items")) {
            text.append(item.get("NAME")).append("：申请 ").append(item.get("QUANTITY"))
                .append(item.get("UNIT")).append("，当前库存 ").append(item.get("STOCK"))
                .append(item.get("UNIT")).append("。\n");
        }
        text.append("请确认是否审批通过。");
        return new Reply(text.toString(), token, null);
    }

    private Reply localReply(String user, String message) {
        if (message.contains("导出") && message.contains("台账"))
            return new Reply("台账已准备好，可下载 Excel。", null, "/api/export?user=" + user);
        if (message.contains("待我审批") || message.contains("待审批")) {
            if (!ledger.user(user).role().equals("审批人")) throw new BusinessException("只有审批人可以查看待审批列表");
            String text = ledger.requests(user).stream().filter(r -> "待审批".equals(r.get("STATUS")))
                .map(r -> "LY-" + r.get("ID") + " " + r.get("APPLICANT") + "：" + r.get("DETAILS"))
                .reduce((a, b) -> a + "\n" + b).orElse("当前没有待审批申请。");
            return new Reply(text, null, null);
        }
        if (message.contains("批了") || message.contains("审批通过")) {
            if (!ledger.user(user).role().equals("审批人")) throw new BusinessException("只有审批人可以审批");
            Matcher number = Pattern.compile("(?:LY-)?(\\d+)").matcher(message);
            if (number.find()) return prepare(user, Long.parseLong(number.group(1)));
            for (Map<String, Object> request : ledger.requests(user)) {
                if ("待审批".equals(request.get("STATUS")) && message.contains((String) request.get("APPLICANT")))
                    return prepare(user, ((Number) request.get("ID")).longValue());
            }
            throw new BusinessException("找不到对应的待审批申请，请先查看待审批列表");
        }
        if (message.contains("我要领") || message.contains("申请领")) {
            List<LedgerService.Item> items = new ArrayList<>();
            for (Map<String, Object> material : ledger.materials()) {
                String name = (String) material.get("NAME");
                String alias = name.startsWith("A4") ? "A4 纸" : name.startsWith("签字笔") ? "签字笔" : name;
                Matcher matcher = Pattern.compile("(\\d+)\\s*[支包本盒个节]?\\s*(?:的)?" + Pattern.quote(alias)).matcher(message);
                if (matcher.find()) items.add(new LedgerService.Item((String) material.get("CODE"), Integer.parseInt(matcher.group(1))));
            }
            if (items.isEmpty()) throw new BusinessException("未识别领用物料，请在页面选择物料和数量");
            long id = ledger.createRequest(user, items);
            return new Reply("领用申请 LY-" + id + " 已提交，状态为待审批。", null, null);
        }
        for (Map<String, Object> material : ledger.materials()) {
            String name = (String) material.get("NAME");
            if (message.contains(name) || (name.startsWith("A4") && message.contains("A4 纸")))
                return new Reply(name + " 当前库存 " + material.get("STOCK") + material.get("UNIT") + "。", null, null);
        }
        return new Reply("未配置模型服务。可查询物料库存、查看待审批、申请领用或导出台账；复杂操作请使用页面。", null, null);
    }

    public class Tools {
        private final String user;
        private String confirmationId;
        private String downloadUrl;
        Tools(String user) { this.user = user; }

        @Tool(name = "listMaterials", description = "查询物料编码、名称、单位和当前库存", readOnly = true)
        public String listMaterials() {
            try { return json.writeValueAsString(ledger.materials()); }
            catch (Exception ex) { return "查询失败"; }
        }

        @Tool(name = "listPending", description = "列出当前审批人待审批的领用申请", readOnly = true)
        public String listPending() {
            if (!ledger.user(user).role().equals("审批人")) return "无权查看";
            try { return json.writeValueAsString(ledger.requests(user).stream().filter(r -> "待审批".equals(r.get("STATUS"))).toList()); }
            catch (Exception ex) { return "查询失败"; }
        }

        @Tool(name = "createIssue", description = "提交当前用户的领用申请，items 格式 M001:5,M002:10")
        public String createIssue(@ToolParam(name = "items", description = "物料编码与正整数数量，逗号分隔") String items) {
            List<LedgerService.Item> parsed = new ArrayList<>();
            for (String part : items.split(",")) {
                String[] fields = part.trim().split(":");
                if (fields.length != 2) return "格式错误，使用 M001:5,M002:10";
                try { parsed.add(new LedgerService.Item(fields[0].trim(), Integer.parseInt(fields[1].trim()))); }
                catch (NumberFormatException ex) { return "数量必须为正整数"; }
            }
            try { return "领用申请 LY-" + ledger.createRequest(user, parsed) + " 已提交，待审批。"; }
            catch (BusinessException ex) { return ex.getMessage(); }
        }

        @Tool(name = "prepareApproval", description = "展示待审批申请的详情并要求用户确认，不执行审批", readOnly = true)
        public String prepareApproval(@ToolParam(name = "requestId", description = "领用申请的数字 ID") long requestId) {
            try {
                Reply reply = prepare(user, requestId);
                confirmationId = reply.confirmationId();
                return reply.text();
            } catch (BusinessException ex) { return ex.getMessage(); }
        }

        @Tool(name = "exportLedger", description = "生成当前库存台账 Excel 下载链接", readOnly = true)
        public String exportLedger() {
            downloadUrl = "/api/export?user=" + user;
            return "台账已准备好，可下载 Excel。";
        }
    }
}
