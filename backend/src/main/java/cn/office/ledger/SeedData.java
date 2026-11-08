package cn.office.ledger;

import jakarta.annotation.PostConstruct;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class SeedData {
    private final LedgerMapper db;

    public SeedData(LedgerMapper db) { this.db = db; }

    @PostConstruct
    void seed() {
        // 首次启动按原始工作簿建主数据；已有编码不更新，以保留真实库存。
        try (var input = new ClassPathResource("data/物料主数据.xlsx").getInputStream();
             var workbook = WorkbookFactory.create(input)) {
            var sheet = workbook.getSheetAt(0);
            var format = new DataFormatter();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                if (row == null) continue;
                String code = format.formatCellValue(row.getCell(0)).trim();
                if (code.isBlank()) continue;
                String name = format.formatCellValue(row.getCell(1)).trim();
                String spec = format.formatCellValue(row.getCell(2)).trim();
                String unit = format.formatCellValue(row.getCell(3)).trim();
                int safe = Integer.parseInt(format.formatCellValue(row.getCell(4)).trim());
                String category = format.formatCellValue(row.getCell(5)).trim();
                if (name.isBlank() || unit.isBlank() || safe < 0) throw new IllegalStateException("物料主数据第 " + (index + 1) + " 行无效");
                db.insertMaterialIfAbsent(code, name, spec, unit, safe, category);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("无法初始化物料主数据", ex);
        }
    }
}
