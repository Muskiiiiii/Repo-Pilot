'use client';

import React from 'react';
import { AuditExportGovernanceView } from '@/components/audit/AuditExportGovernanceView';

export default function AuditExportsPage() {
  // Demo / default organization ID from workspace
  const defaultOrgId = 'org-123';

  return (
    <div className="p-6">
      <AuditExportGovernanceView organizationId={defaultOrgId} userRole="ORG_ADMIN" />
    </div>
  );
}
