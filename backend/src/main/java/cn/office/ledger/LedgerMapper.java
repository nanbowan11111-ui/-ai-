package cn.office.ledger;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LedgerMapper extends BaseMapper<Material> {
    // 列别名固定为大写，保持现有 API、导出和聊天工具的字段契约。
    @Select("SELECT code AS CODE,name AS NAME,specification AS SPECIFICATION,unit AS UNIT,safe_stock AS SAFE_STOCK,category AS CATEGORY,stock AS STOCK FROM material ORDER BY code")
    List<Map<String, Object>> materials();

    @Select("SELECT m.occurred_at AS OCCURRED_AT,m.kind AS KIND,m.document_number AS DOCUMENT_NUMBER,m.material_code AS MATERIAL_CODE,t.name AS MATERIAL_NAME,m.quantity AS QUANTITY,m.balance AS BALANCE,m.operator AS OPERATOR FROM movement m JOIN material t ON t.code=m.material_code ORDER BY m.id DESC LIMIT 100")
    List<Map<String, Object>> movements();

    @Select("SELECT r.id AS ID,r.applicant AS APPLICANT,r.status AS STATUS,r.reject_reason AS REJECT_REASON,r.reviewer AS REVIEWER,r.created_at AS CREATED_AT,t.name AS ITEM_NAME,i.quantity AS ITEM_QUANTITY,t.unit AS ITEM_UNIT FROM issue_request r JOIN issue_item i ON i.request_id=r.id JOIN material t ON t.code=i.material_code ORDER BY r.id DESC,i.material_code")
    List<Map<String, Object>> allRequestLines();

    @Select("SELECT r.id AS ID,r.applicant AS APPLICANT,r.status AS STATUS,r.reject_reason AS REJECT_REASON,r.reviewer AS REVIEWER,r.created_at AS CREATED_AT,t.name AS ITEM_NAME,i.quantity AS ITEM_QUANTITY,t.unit AS ITEM_UNIT FROM issue_request r JOIN issue_item i ON i.request_id=r.id JOIN material t ON t.code=i.material_code WHERE r.applicant=#{applicant} ORDER BY r.id DESC,i.material_code")
    List<Map<String, Object>> requestLinesFor(@Param("applicant") String applicant);

    @Select("SELECT id AS ID,applicant AS APPLICANT,status AS STATUS,reject_reason AS REJECT_REASON,reviewer AS REVIEWER,created_at AS CREATED_AT,reviewed_at AS REVIEWED_AT FROM issue_request WHERE id=#{id}")
    Map<String, Object> requestDetails(@Param("id") long id);

    @Select("SELECT i.material_code AS CODE,t.name AS NAME,t.unit AS UNIT,i.quantity AS QUANTITY,t.stock AS STOCK FROM issue_item i JOIN material t ON t.code=i.material_code WHERE i.request_id=#{id} ORDER BY i.material_code")
    List<Map<String, Object>> requestItems(@Param("id") long id);

    @Insert("INSERT INTO issue_request(applicant,status) VALUES (#{request.applicant},'待审批')")
    @Options(useGeneratedKeys = true, keyProperty = "request.id", keyColumn = "id")
    int insertRequest(@Param("request") NewRequest request);

    @Insert("INSERT INTO issue_item(request_id,material_code,quantity) VALUES (#{id},#{code},#{quantity})")
    int insertRequestItem(@Param("id") long id, @Param("code") String code, @Param("quantity") int quantity);

    // 锁申请行，审批与撤回必须串行检查待审批状态。
    @Select("SELECT applicant AS APPLICANT,status AS STATUS FROM issue_request WHERE id=#{id} FOR UPDATE")
    Map<String, Object> lockRequest(@Param("id") long id);

    @Select("SELECT material_code AS MATERIAL_CODE,quantity AS QUANTITY FROM issue_item WHERE request_id=#{id} ORDER BY material_code")
    List<Map<String, Object>> approvalItems(@Param("id") long id);

    // 库存条件与扣减在同一条 SQL 中，防止并发审批超扣。
    @Update("UPDATE material SET stock=stock-#{quantity} WHERE code=#{code} AND stock>=#{quantity}")
    int deductStock(@Param("code") String code, @Param("quantity") int quantity);

    @Update("UPDATE issue_request SET status='已通过',reviewer=#{reviewer},reviewed_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int approveRequest(@Param("id") long id, @Param("reviewer") String reviewer);

    @Update("UPDATE issue_request SET status='已驳回',reject_reason=#{reason},reviewer=#{reviewer},reviewed_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int rejectRequest(@Param("id") long id, @Param("reviewer") String reviewer, @Param("reason") String reason);

    @Update("UPDATE issue_request SET status='已撤回' WHERE id=#{id}")
    int withdrawRequest(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM receipt WHERE number=#{number}")
    int receiptCount(@Param("number") String number);

    @Insert("INSERT INTO receipt(number,received_on,supplier,department,operator) VALUES (#{number},#{date},#{supplier},#{department},#{operator})")
    int insertReceipt(@Param("number") String number, @Param("date") String date,
                      @Param("supplier") String supplier, @Param("department") String department,
                      @Param("operator") String operator);

    @Select("SELECT stock FROM material WHERE code=#{code} FOR UPDATE")
    Integer lockStock(@Param("code") String code);

    @Update("UPDATE material SET stock=#{stock} WHERE code=#{code}")
    int setStock(@Param("code") String code, @Param("stock") int stock);

    @Insert("INSERT INTO movement(kind,document_number,material_code,quantity,balance,operator) VALUES (#{kind},#{number},#{code},#{quantity},#{balance},#{operator})")
    int insertMovement(@Param("kind") String kind, @Param("number") String number,
                       @Param("code") String code, @Param("quantity") int quantity,
                       @Param("balance") int balance, @Param("operator") String operator);

    @Insert("INSERT INTO material(code,name,specification,unit,safe_stock,category,stock) SELECT #{code},#{name},#{spec},#{unit},#{safe},#{category},0 WHERE NOT EXISTS (SELECT 1 FROM material WHERE code=#{code})")
    int insertMaterialIfAbsent(@Param("code") String code, @Param("name") String name,
                               @Param("spec") String spec, @Param("unit") String unit,
                               @Param("safe") int safe, @Param("category") String category);

    @Select("SELECT COUNT(*) FROM issue_request WHERE status=#{status}")
    int countRequestsWithStatus(@Param("status") String status);

    class NewRequest {
        private Long id;
        private final String applicant;

        public NewRequest(String applicant) { this.applicant = applicant; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getApplicant() { return applicant; }
    }
}
