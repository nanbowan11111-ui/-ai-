package cn.office.ledger;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ReceiptImport {
    public record Line(int row, String code, String name, int quantity, List<String> warnings) {}
    public record ErrorLine(int row, String reason) {}
    public record Parsed(String number, String date, String supplier, String department,
                         List<Line> lines, List<ErrorLine> errors) {}
    public record Preview(String token, Parsed parsed, boolean alreadyPosted) {}
    private record Pending(byte[] bytes, Instant created) {}

    private final LedgerMapper db;
    private final LedgerService ledger;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ReceiptImport(LedgerMapper db, LedgerService ledger) { this.db = db; this.ledger = ledger; }

    public Preview preview(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("请选择入库单 Excel 文件");
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().toLowerCase().endsWith(".xlsx"))
            throw new BusinessException("只支持 .xlsx 入库单");
        try {
            byte[] bytes = file.getBytes();
            Parsed parsed = parse(bytes);
            pending.entrySet().removeIf(e -> e.getValue().created().isBefore(Instant.now().minusSeconds(1800)));
            String token = UUID.randomUUID().toString();
            pending.put(token, new Pending(bytes, Instant.now()));
            boolean posted = !parsed.number().isBlank() && db.receiptCount(parsed.number()) > 0;
            return new Preview(token, parsed, posted);
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException("无法解析 Excel，请检查文件格式"); }
    }

    public String confirm(String token, String operator) {
        if (token == null || token.isBlank()) throw new BusinessException("请先预览入库单");
        Pending saved = pending.get(token);
        if (saved == null || saved.created().isBefore(Instant.now().minusSeconds(1800)))
            throw new BusinessException("预览已失效，请重新上传");
        Parsed parsed = parse(saved.bytes());
        ledger.postReceipt(parsed, operator);
        pending.remove(token);
        return parsed.number();
    }

    Parsed parse(byte[] bytes) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("入库单");
            if (sheet == null) throw new BusinessException("缺少“入库单”工作表");
            DataFormatter format = new DataFormatter();
            String number = cell(sheet, 1, 1, format).trim();
            String date = cell(sheet, 2, 1, format).trim();
            String supplier = cell(sheet, 1, 5, format).trim();
            String department = cell(sheet, 3, 1, format).trim();
            List<ErrorLine> errors = new ArrayList<>();
            if (number.isBlank()) errors.add(new ErrorLine(2, "入库单号为空"));
            if (number.length() > 60) errors.add(new ErrorLine(2, "入库单号过长"));
            List<Line> lines = new ArrayList<>();
            Map<String, String> names = new HashMap<>();
            for (Material material : db.selectList(null)) names.put(material.getCode(), material.getName());
            Set<String> seen = new HashSet<>();
            for (int rowIndex = 6; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                String sequence = cell(sheet, rowIndex, 0, format).trim();
                if (sequence.equals("合计")) break;
                String code = cell(sheet, rowIndex, 1, format).trim();
                String qtyText = cell(sheet, rowIndex, 5, format).trim();
                if (sequence.isBlank() && code.isBlank() && qtyText.isBlank()) continue;
                List<String> rowErrors = new ArrayList<>();
                if (code.isBlank()) rowErrors.add("物料编码为空");
                else if (!names.containsKey(code)) rowErrors.add("未登记的物料编码 " + code);
                int quantity = 0;
                try {
                    quantity = new BigDecimal(qtyText).intValueExact();
                    if (quantity <= 0) rowErrors.add("入库数量必须大于 0");
                } catch (NumberFormatException | ArithmeticException ex) {
                    rowErrors.add("入库数量必须是正整数");
                }
                if (!rowErrors.isEmpty()) errors.add(new ErrorLine(rowIndex + 1, String.join("；", rowErrors)));
                List<String> warnings = new ArrayList<>();
                if (!code.isBlank() && !seen.add(code)) warnings.add("同一物料在本单重复出现，确认时合并入账");
                lines.add(new Line(rowIndex + 1, code, names.getOrDefault(code, cell(sheet, rowIndex, 2, format)), quantity, warnings));
            }
            if (lines.isEmpty()) errors.add(new ErrorLine(0, "入库单没有明细行"));
            return new Parsed(number, date, supplier, department, lines, errors);
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException("无法解析 Excel，请检查工作表和单元格格式"); }
    }

    private String cell(Sheet sheet, int row, int column, DataFormatter format) {
        Row data = sheet.getRow(row);
        Cell cell = data == null ? null : data.getCell(column);
        return cell == null ? "" : format.formatCellValue(cell);
    }
}
