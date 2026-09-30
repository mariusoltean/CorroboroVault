package ro.corroboro.vault.core.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only snapshot of the vault's current runtime configuration — lets the caller
 * (or an operator holding the service token) confirm whether virus scanning is actually
 * active on this deployment, without digging through env vars or logs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VaultStatusResponseModelV1 {
    private boolean clamAvEnabled;
    private int retentionDays;
    private boolean purgeEnabled;
    private boolean manualPurgeTriggerEnabled;
}
