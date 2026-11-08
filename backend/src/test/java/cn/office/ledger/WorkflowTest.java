package cn.office.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:workflow;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.sql.init.schema-locations=classpath:schema-h2.sql"
})
class WorkflowTest {
    @Autowired ReceiptImport receipts;
    @Autowired LedgerService ledger;
    @Autowired LedgerMapper db;
    @Autowired ApiController api;
    @Autowired ChatService chat;

    private MockMultipartFile sample(String name) throws Exception {
        byte[] bytes = new ClassPathResource("samples/" + name).getInputStream().readAllBytes();
        return new MockMultipartFile("file", name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private int stock(String code) {
        return db.selectById(code).getStock();
    }

    @Test
    void originalWorkbooksAndApprovalFlow() throws Exception {
        assertThat(ledger.materials()).hasSize(12);
        assertThat(ledger.materials().getFirst()).containsKeys("CODE", "SAFE_STOCK", "STOCK");
        assertThat(stock("M001")).isZero();

        var first = receipts.preview(sample("入库单-RK-20261101-001.xlsx"));
        assertThat(first.parsed().errors()).isEmpty();
        assertThat(first.parsed().lines()).hasSize(10);
        assertThat(stock("M001")).isZero(); // 预览没有写入。
        assertThat(receipts.confirm(first.token(), "李华")).isEqualTo("RK-20261101-001");
        assertThat(stock("M001")).isEqualTo(50);
        assertThat(stock("M002")).isEqualTo(120);
        assertThat(ledger.movements()).hasSize(10);

        var second = receipts.preview(sample("入库单-RK-20261102-002.xlsx"));
        assertThat(second.parsed().errors()).extracting(ReceiptImport.ErrorLine::row).contains(10, 11);
        assertThat(second.parsed().lines()).anySatisfy(line -> assertThat(line.warnings()).isNotEmpty());
        assertThatThrownBy(() -> receipts.confirm(second.token(), "李华")).isInstanceOf(BusinessException.class);
        assertThat(stock("M001")).isEqualTo(50);
        assertThat(stock("M011")).isZero();
        assertThat(ledger.movements()).hasSize(10);

        var duplicate = receipts.preview(sample("入库单-RK-20261101-001.xlsx"));
        assertThat(duplicate.alreadyPosted()).isTrue();
        assertThatThrownBy(() -> receipts.confirm(duplicate.token(), "李华"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("已入账");

        long a = ledger.createRequest("李华", List.of(new LedgerService.Item("M001", 40)));
        // Map 的大写键及空值键是前端和聊天工具已经使用的接口契约。
        assertThat(ledger.requestDetails(a)).containsKeys("ID", "REJECT_REASON", "REVIEWER", "items");
        assertThat(ledger.requestDetails(a).get("REVIEWER")).isNull();
        long b = ledger.createRequest("陈晨", List.of(new LedgerService.Item("M001", 40)));
        assertThat(stock("M001")).isEqualTo(50); // 申请不扣库存。
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); try { ledger.approve(a, "王经理"); return (Exception) null; } catch (Exception ex) { return ex; } });
            var two = pool.submit(() -> { start.await(); try { ledger.approve(b, "王经理"); return (Exception) null; } catch (Exception ex) { return ex; } });
            start.countDown();
            Exception firstFailure = one.get();
            Exception secondFailure = two.get();
            assertThat(List.of(firstFailure == null, secondFailure == null)).containsExactlyInAnyOrder(true, false);
            assertThat(firstFailure != null ? firstFailure : secondFailure).isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前库存不足");
            long pending = firstFailure != null ? a : b;
            assertThat(ledger.requestDetails(pending).get("STATUS")).isEqualTo("待审批");
        }
        assertThat(stock("M001")).isEqualTo(10);
        assertThat(db.countRequestsWithStatus("已通过")).isEqualTo(1);
        assertThat(ledger.movements()).hasSize(11);
        assertThatThrownBy(() -> ledger.approve(a, "李华")).isInstanceOf(BusinessException.class);
        long withdrawn = ledger.createRequest("李华", List.of(new LedgerService.Item("M002", 3)));
        assertThatThrownBy(() -> ledger.withdraw(withdrawn, "陈晨"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("自己提交");
        assertThatThrownBy(() -> ledger.withdraw(withdrawn, "王经理"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("只有申请人");
        assertThat(api.withdraw(withdrawn, new ApiController.Actor("李华"))).containsEntry("status", "已撤回");
        assertThat(ledger.requestDetails(withdrawn).get("STATUS")).isEqualTo("已撤回");
        assertThat(stock("M002")).isEqualTo(120);
        assertThatThrownBy(() -> ledger.withdraw(withdrawn, "李华"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能撤回");
        assertThatThrownBy(() -> ledger.approve(withdrawn, "王经理"))
            .isInstanceOf(BusinessException.class);
        long approved = List.of(a, b).stream()
            .filter(id -> "已通过".equals(ledger.requestDetails(id).get("STATUS"))).findFirst().orElseThrow();
        assertThatThrownBy(() -> ledger.withdraw(approved, (String) ledger.requestDetails(approved).get("APPLICANT")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("不能撤回");
        assertThatThrownBy(() -> api.create(new ApiController.RequestInput("李华",
            List.of(new ApiController.RequestedItem("M002", new BigDecimal("1.5"))))))
            .isInstanceOf(BusinessException.class);

        long chatRequest = ledger.createRequest("李华", List.of(new LedgerService.Item("M002", 5)));
        var prepared = chat.respond("王经理", "把 LY-" + chatRequest + " 批了", null);
        assertThat(prepared.text()).contains("当前库存 120支");
        assertThat(prepared.confirmationId()).isNotBlank();
        assertThat(stock("M002")).isEqualTo(120);
        chat.respond("王经理", null, prepared.confirmationId());
        assertThat(stock("M002")).isEqualTo(115);
        assertThatThrownBy(() -> chat.respond("王经理", null, prepared.confirmationId()))
            .isInstanceOf(BusinessException.class);

        List<ChatService.StreamChunk> streamed = new ArrayList<>();
        chat.stream("王经理", "查看待审批", null, streamed::add);
        assertThat(streamed).hasSize(1);
        assertThat(streamed.getFirst().type()).isEqualTo("done");
        assertThat(streamed.getFirst().text()).contains("LY-");
        streamed.clear();
        chat.stream("李华", "导出台账", null, streamed::add);
        assertThat(streamed.getFirst().downloadUrl()).contains("/api/export");
        assertThatThrownBy(() -> chat.stream("李华", "", null, streamed::add))
            .isInstanceOf(BusinessException.class).hasMessageContaining("请输入消息");

        byte[] exported = api.export("李华").getBody();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(exported))) {
            var sheet = workbook.getSheet("库存台账");
            assertThat(sheet.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(10);
            assertThat(sheet.getRow(1).getCell(6).getStringCellValue()).isEqualTo("低于安全库存");
        }
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(chat.new Tools("王经理"));
        assertThat(toolkit.getToolNames()).contains("listMaterials", "listPending", "createIssue", "prepareApproval", "exportLedger");
    }
}
