package com.netpulse.dto;

import com.netpulse.security.ApprovedMonitorUrls;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class CreateMonitorRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    @NotBlank
    @Size(max = 2048)
    private String url;

    @NotNull
    @Min(10)
    @Max(86400)
    private Integer intervalSeconds;

    private Boolean enabled = Boolean.TRUE;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Integer getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(Integer intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    @AssertTrue(message = "url must start with http:// or https://")
    public boolean isUrlSchemeValid() {
        if (url == null) {
            return true;
        }
        String trimmed = url.trim().toLowerCase();
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }

    /**
     * Rejects URLs the scheduler/worker would abandon, so monitors cannot sit in
     * "Waiting" forever (e.g. https://www.google.com before it was allowlisted).
     */
    @AssertTrue(message = "url is not on the approved monitor allowlist")
    public boolean isUrlAllowlisted() {
        if (url == null || url.isBlank()) {
            return true; // @NotBlank owns emptiness
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase();
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            return true; // scheme validator owns that error
        }
        return ApprovedMonitorUrls.isApproved(trimmed);
    }
}
