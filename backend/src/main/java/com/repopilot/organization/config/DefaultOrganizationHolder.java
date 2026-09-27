package com.repopilot.organization.config;

import com.repopilot.organization.entity.Organization;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

public class DefaultOrganizationHolder {

    public static final UUID DEFAULT_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static DataSource dataSource;

    public static void setDataSource(DataSource ds) {
        dataSource = ds;
        ensureDefaultOrganizationExists();
    }

    public static Organization getDefaultOrganization() {
        ensureDefaultOrganizationExists();
        Organization org = new Organization();
        org.setId(DEFAULT_ORG_ID);
        org.setName("Default Organization");
        org.setSlug("default-org");
        return org;
    }

    public static synchronized void ensureDefaultOrganizationExists() {
        if (dataSource == null) return;
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM organizations WHERE id = ?")) {
                ps.setObject(1, DEFAULT_ORG_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        try (PreparedStatement ins = conn.prepareStatement(
                                "INSERT INTO organizations (id, name, slug, created_at, updated_at) VALUES (?, 'Default Organization', 'default-org', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                            ins.setObject(1, DEFAULT_ORG_ID);
                            ins.executeUpdate();
                        }
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM organization_policies WHERE organization_id = ?")) {
                ps.setObject(1, DEFAULT_ORG_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        try (PreparedStatement ins = conn.prepareStatement(
                                "INSERT INTO organization_policies (id, organization_id, monthly_token_budget, monthly_spend_limit_usd, allowed_llm_models_json, enforce_security_review, max_concurrent_executions, audit_retention_days, version, updated_at) VALUES (random_uuid(), ?, 1000000, 100.00, '[\"claude-3-5-sonnet\", \"gpt-4o\"]', true, 5, 90, 0, CURRENT_TIMESTAMP)")) {
                            ins.setObject(1, DEFAULT_ORG_ID);
                            ins.executeUpdate();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
