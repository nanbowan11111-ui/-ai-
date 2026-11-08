package cn.office.ledger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ApiController {
    private static final Logger log = LoggerFactory.getLogger(ApiController.class);
    private final LedgerService ledger;
    private final ReceiptImport receipts;
    private final ChatService chat;

    public ApiController(LedgerService ledger, ReceiptImport receipts, ChatService chat) {
        this.ledger = ledger;
        this.receipts = receipts;
        this.chat = chat;
    }

    public record Actor(String user) {}
    public record RequestedItem(String code, BigDecimal quantity) {}
    public record RequestInput(String user, List<RequestedItem> items) {}
    public record RejectInput(String user, String reason) {}
    public record ConfirmInput(String token, String user) {}
    public record ChatInput(String user, String message, String confirmationId) {}

    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam String user) {
        ledger.user(user);
        return Map.of("users", ledger.users(), "materials", ledger.materials(),
            "movements", ledger.movements(), "requests", ledger.requests(user));
    }

    @GetMapping("/requests/{id}")
    public Map<String, Object> request(@PathVariable long id, @RequestParam String user) {
        var current = ledger.user(user);
        var details = ledger.requestDetails(id);
        if (!current.role().equals("审批人") && !user.equals(details.get("APPLICANT")))
            throw new BusinessException("无权查看这张申请");
        return details;
    }

    @PostMapping("/imports/preview")
    public ReceiptImport.Preview preview(@RequestParam MultipartFile file, @RequestParam String user) {
        ledger.user(user);
        return receipts.preview(file);
    }

    @PostMapping("/imports/confirm")
    public Map<String, String> confirm(@RequestBody ConfirmInput input) {
        return Map.of("number", receipts.confirm(input.token(), input.user()));
    }

    @PostMapping("/requests")
    public Map<String, Long> create(@RequestBody RequestInput input) {
        if (input.items() == null) throw new BusinessException("请至少选择一种物料");
        List<LedgerService.Item> items = new ArrayList<>();
        for (RequestedItem item : input.items()) {
            if (item == null || item.quantity() == null) throw new BusinessException("领用数量必须是正整数");
            try { items.add(new LedgerService.Item(item.code(), item.quantity().intValueExact())); }
            catch (ArithmeticException ex) { throw new BusinessException("领用数量必须是正整数且不能超过系统上限"); }
        }
        return Map.of("id", ledger.createRequest(input.user(), items));
    }

    @PostMapping("/requests/{id}/approve")
    public Map<String, String> approve(@PathVariable long id, @RequestBody Actor actor) {
        ledger.approve(id, actor.user());
        return Map.of("status", "已通过");
    }

    @PostMapping("/requests/{id}/reject")
    public Map<String, String> reject(@PathVariable long id, @RequestBody RejectInput input) {
        ledger.reject(id, input.user(), input.reason());
        return Map.of("status", "已驳回");
    }

    @PostMapping("/requests/{id}/withdraw")
    public Map<String, String> withdraw(@PathVariable long id, @RequestBody Actor actor) {
        ledger.withdraw(id, actor.user());
        return Map.of("status", "已撤回");
    }

    @PostMapping("/chat")
    public ChatService.Reply chat(@RequestBody ChatInput input) {
        return chat.respond(input.user(), input.message(), input.confirmationId());
    }

    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatInput input) {
        SseEmitter emitter = new SseEmitter(35_000L);
        Thread.ofVirtual().start(() -> {
            try {
                chat.stream(input.user(), input.message(), input.confirmationId(), chunk -> {
                    try { emitter.send(SseEmitter.event().data(chunk)); }
                    catch (IOException ex) { throw new IllegalStateException("聊天连接已断开", ex); }
                });
                emitter.complete();
            } catch (Exception ex) {
                String message = ex instanceof BusinessException ? ex.getMessage() : "聊天连接中断，请重试";
                try { emitter.send(SseEmitter.event().data(new ChatService.StreamChunk("error", message, null, null))); }
                catch (IOException ignored) { /* 客户端已断开，无处显示错误。 */ }
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String user) throws IOException {
        ledger.user(user);
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("库存台账");
            String[] headers = {"物料编码", "物料名称", "规格型号", "单位", "当前库存", "安全库存", "库存状态"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            int index = 1;
            for (Map<String, Object> item : ledger.materials()) {
                Row row = sheet.createRow(index++);
                row.createCell(0).setCellValue((String) item.get("CODE"));
                row.createCell(1).setCellValue((String) item.get("NAME"));
                row.createCell(2).setCellValue((String) item.get("SPECIFICATION"));
                row.createCell(3).setCellValue((String) item.get("UNIT"));
                int stock = ((Number) item.get("STOCK")).intValue();
                int safe = ((Number) item.get("SAFE_STOCK")).intValue();
                row.createCell(4).setCellValue(stock);
                row.createCell(5).setCellValue(safe);
                row.createCell(6).setCellValue(stock < safe ? "低于安全库存" : "正常");
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=inventory-ledger.xlsx")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> businessError(BusinessException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> integrityError(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "单号或记录已存在，操作未执行；请刷新页面后检查"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> uploadError(MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "文件超过 5MB，请选择原始入库单 Excel"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception ex) {
        log.error("Unhandled API error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "操作失败，请稍后重试"));
    }
}
