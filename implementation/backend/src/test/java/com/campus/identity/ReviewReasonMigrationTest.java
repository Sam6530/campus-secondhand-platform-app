package com.campus.identity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

/** 独立H2中验证增量脚本扩大字段且保留原值；MySQL升级另按真实环境验收。 */
class ReviewReasonMigrationTest {
    @Test void widensExistingColumnsWithoutLosingHistory() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:reason_migration;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var db=new JdbcTemplate(ds);
        db.execute("create table item_versions(id int primary key,review_reason varchar(1000))");
        db.execute("create table audit_facts(id int primary key,reason varchar(1000))");
        db.update("insert into item_versions values(1,'原审核理由')");
        db.update("insert into audit_facts values(1,'原审计理由')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__review_reason_limit.sql")).execute(ds);
        assertEquals("原审核理由",db.queryForObject("select review_reason from item_versions where id=1",String.class));
        assertEquals("原审计理由",db.queryForObject("select reason from audit_facts where id=1",String.class));
        for(int length:new int[]{1000,1001,1500}) {
            db.update("insert into item_versions values(?,?)",length,"审".repeat(length));
            db.update("insert into audit_facts values(?,?)",length,"审".repeat(length));
            assertEquals(length,db.queryForObject("select length(review_reason) from item_versions where id=?",Integer.class,length));
        }
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("insert into audit_facts values(1501,?)","审".repeat(1501)));
        assertEquals(0,db.queryForObject("select count(*) from audit_facts where id=1501",Integer.class));
    }
}
