package net.trollyloki.dicsit.data;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@NullMarked
public class GuildData {

    private final Map<UUID, ServerData> servers = new ConcurrentHashMap<>();

    private int dataVersion = 0;

    private @Nullable String adminRoleId;
    private @Nullable String saveManagerRoleId;
    private @Nullable String dashboardChannelId;
    private @Nullable String logChannelId;
    private @Nullable String alertRoleId;
    private long offlineAlertDelaySeconds = -1;

    public int getDataVersion() {
        return dataVersion;
    }

    public void setDataVersion(int dataVersion) {
        this.dataVersion = dataVersion;
    }

    public Map<UUID, ServerData> getServers() {
        return servers;
    }

    public @Nullable String getAdminRoleId() {
        return adminRoleId;
    }

    public void setAdminRoleId(@Nullable String adminRoleId) {
        this.adminRoleId = adminRoleId;
    }

    public @Nullable String getSaveManagerRoleId() {
        return saveManagerRoleId;
    }

    public void setSaveManagerRoleId(@Nullable String saveManagerRoleId) {
        this.saveManagerRoleId = saveManagerRoleId;
    }

    public @Nullable String getDashboardChannelId() {
        return dashboardChannelId;
    }

    public void setDashboardChannelId(@Nullable String dashboardChannelId) {
        this.dashboardChannelId = dashboardChannelId;
    }

    public @Nullable String getLogChannelId() {
        return logChannelId;
    }

    public void setLogChannelId(@Nullable String logChannelId) {
        this.logChannelId = logChannelId;
    }

    public @Nullable String getAlertRoleId() {
        return alertRoleId;
    }

    public void setAlertRoleId(@Nullable String alertRoleId) {
        this.alertRoleId = alertRoleId;
    }

    public long getOfflineAlertDelaySeconds() {
        return offlineAlertDelaySeconds;
    }

    public void setOfflineAlertDelaySeconds(long offlineAlertDelaySeconds) {
        this.offlineAlertDelaySeconds = offlineAlertDelaySeconds;
    }

}
