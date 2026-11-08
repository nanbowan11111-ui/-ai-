package cn.office.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
    private final LedgerMapper db;

    public LedgerService(LedgerMapper db) { this.db = db; }

    public record Item(String code, int quantity) {}
    public record User(String name, String role, String department) {}
    private static final List<User> USERS = List.of(
        new User("李华", "申请人", "综合办公室"),
        new User("陈晨", "申请人", "财务部"),
        new User("王经理", "审批人", "综合办公室"));

    public List<User> users() { return USERS; }

    public User user(String name) {
        return USERS.stream().filter(u -> u.name().equals(name)).findFirst()
            .orElseThrow(() -> new BusinessException("无效的当前用户"));
    }

    public List<Map<String, Object>> materials() {
        return db.materials();
    }

    public List<Map<String, Object>> movements() {
        return db.movements();
    }

    public List<Map<String, Object>> requests(String name) {
        User current = user(name);
        List<Map<String, Object>> lines = current.role().equals("审批人") ? db.allRequestLines() : db.requestLinesFor(name);
        Map<Long, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> line : lines) {
            long id = ((Number) line.get("ID")).longValue();
            Map<String, Object> request = grouped.computeIfAbsent(id, ignored -> {
                Map<String, Object> value = new LinkedHashMap<>();
                for (String key : List.of("ID", "APPLICANT", "STATUS", "REJECT_REASON", "REVIEWER", "CREATED_AT")) value.put(key, line.get(key));
                value.put("DETAILS", "");
                return value;
            });
            // 在 Java 中拼接明细，避免依赖 H2 与 MySQL 不同的字符串聚合语法。
            String detail = line.get("ITEM_NAME") + " × " + line.get("ITEM_QUANTITY") + line.get("ITEM_UNIT");
            String existing = (String) request.get("DETAILS");
            request.put("DETAILS", existing.isEmpty() ? detail : existing + "、" + detail);
        }
        return new ArrayList<>(grouped.values());
    }

    public Map<String, Object> requestDetails(long id) {
        Map<String, Object> found = db.requestDetails(id);
        if (found == null) throw new BusinessException("申请不存在");
        Map<String, Object> result = new LinkedHashMap<>(found);
        result.put("items", db.requestItems(id));
        return result;
    }

    @Transactional
    public long createRequest(String applicant, List<Item> rawItems) {
        if (!user(applicant).role().equals("申请人")) throw new BusinessException("只有申请人可以提交领用申请");
        if (rawItems == null || rawItems.isEmpty()) throw new BusinessException("请至少选择一种物料");
        Map<String, Integer> items = new LinkedHashMap<>();
        for (Item item : rawItems) {
            if (item == null || item.code() == null || item.quantity() <= 0) throw new BusinessException("领用数量必须是正整数");
            try { items.merge(item.code().trim(), item.quantity(), Math::addExact); }
            catch (ArithmeticException ex) { throw new BusinessException("领用数量过大"); }
        }
        for (var item : items.entrySet()) {
            Material material = db.selectById(item.getKey());
            if (material == null) throw new BusinessException("物料不存在：" + item.getKey());
            if (material.getStock() < item.getValue()) throw new BusinessException(item.getKey() + " 当前库存不足");
        }
        LedgerMapper.NewRequest request = new LedgerMapper.NewRequest(applicant);
        db.insertRequest(request);
        if (request.getId() == null) throw new IllegalStateException("申请创建后未返回编号");
        long id = request.getId();
        items.forEach((code, quantity) -> db.insertRequestItem(id, code, quantity));
        return id;
    }

    @Transactional
    public void approve(long id, String reviewer) {
        if (!user(reviewer).role().equals("审批人")) throw new BusinessException("只有审批人可以审批");
        Map<String, Object> request = lockRequest(id);
        if (reviewer.equals(request.get("APPLICANT"))) throw new BusinessException("不能审批自己提交的申请");
        requirePending(request);
        List<Map<String, Object>> items = db.approvalItems(id);
        // 按编码固定更新顺序降低死锁风险；条件更新把库存校验和扣减合为原子操作。
        for (Map<String, Object> item : items) {
            String code = (String) item.get("MATERIAL_CODE");
            int quantity = ((Number) item.get("QUANTITY")).intValue();
            int updated = db.deductStock(code, quantity);
            if (updated != 1) throw new BusinessException(code + " 当前库存不足，审批未执行");
            int balance = db.selectById(code).getStock();
            db.insertMovement("领用出库", "LY-" + id, code, -quantity, balance, reviewer);
        }
        db.approveRequest(id, reviewer);
    }

    @Transactional
    public void reject(long id, String reviewer, String reason) {
        if (!user(reviewer).role().equals("审批人")) throw new BusinessException("只有审批人可以审批");
        if (reason == null || reason.isBlank()) throw new BusinessException("驳回时必须填写原因");
        Map<String, Object> request = lockRequest(id);
        if (reviewer.equals(request.get("APPLICANT"))) throw new BusinessException("不能审批自己提交的申请");
        requirePending(request);
        db.rejectRequest(id, reviewer, reason.trim());
    }

    @Transactional
    public void withdraw(long id, String applicant) {
        if (!user(applicant).role().equals("申请人")) throw new BusinessException("只有申请人可以撤回申请");
        // 与审批共用行锁，确保待审批状态和申请人身份在更新时仍有效。
        Map<String, Object> request = lockRequest(id);
        if (!applicant.equals(request.get("APPLICANT"))) throw new BusinessException("只能撤回自己提交的申请");
        if (!"待审批".equals(request.get("STATUS"))) throw new BusinessException("申请已处理，不能撤回");
        db.withdrawRequest(id);
    }

    private Map<String, Object> lockRequest(long id) {
        Map<String, Object> row = db.lockRequest(id);
        if (row == null) throw new BusinessException("申请不存在");
        return row;
    }

    private void requirePending(Map<String, Object> request) {
        if (!"待审批".equals(request.get("STATUS"))) throw new BusinessException("申请已处理，不能再次审批");
    }

    @Transactional
    public void postReceipt(ReceiptImport.Parsed parsed, String operator) {
        user(operator);
        if (!parsed.errors().isEmpty()) throw new BusinessException("入库单有错误，不能入账");
        if (db.receiptCount(parsed.number()) > 0)
            throw new BusinessException("入库单 " + parsed.number() + " 已入账，不能重复导入");
        db.insertReceipt(
            parsed.number(), parsed.date(), parsed.supplier(), parsed.department(), operator);
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (ReceiptImport.Line line : parsed.lines()) {
            try { totals.merge(line.code(), line.quantity(), Math::addExact); }
            catch (ArithmeticException ex) { throw new BusinessException("入库数量过大"); }
        }
        totals.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(item -> {
            Integer lockedStock = db.lockStock(item.getKey());
            if (lockedStock == null) throw new BusinessException("物料不存在：" + item.getKey());
            int stock = lockedStock;
            int balance;
            try { balance = Math.addExact(stock, item.getValue()); }
            catch (ArithmeticException ex) { throw new BusinessException("库存数量超过系统上限"); }
            db.setStock(item.getKey(), balance);
            db.insertMovement("入库", parsed.number(), item.getKey(), item.getValue(), balance, operator);
        });
    }
}
