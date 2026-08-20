package com.claw.server.common.dto;

import jakarta.validation.constraints.NotBlank;

/** 角色包入参。 */
public final class RoleRequests {

    private RoleRequests() {
    }

    public static record Apply(@NotBlank String roleCode) {
    }
}
