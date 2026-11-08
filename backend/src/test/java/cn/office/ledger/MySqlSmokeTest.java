package cn.office.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@EnabledIfSystemProperty(named = "run.mysql.tests", matches = "true")
class MySqlSmokeTest {
    @Autowired LedgerService ledger;
    @Autowired LedgerMapper db;

    @Test
    @Transactional
    void createApproveAndReadBackOnMySql() {
        // 测试事务结束时自动回滚，验证真实 MySQL 写入路径而不污染演示数据。
        Map<String, Object> available = ledger.materials().stream()
            .filter(item -> ((Number) item.get("STOCK")).intValue() > 0)
            .findFirst().orElseThrow(() -> new IllegalStateException("没有可用于测试的现存库存"));
        String code = (String) available.get("CODE");
        int stock = ((Number) available.get("STOCK")).intValue();

        long id = ledger.createRequest("李华", List.of(new LedgerService.Item(code, 1)));
        assertThat(ledger.requestDetails(id).get("APPLICANT")).isEqualTo("李华");
        assertThat(ledger.requests("王经理")).anySatisfy(request ->
            assertThat(request.get("ID")).isEqualTo(id));

        ledger.approve(id, "王经理");
        assertThat(ledger.requestDetails(id).get("STATUS")).isEqualTo("已通过");
        assertThat(db.selectById(code).getStock()).isEqualTo(stock - 1);
    }
}
