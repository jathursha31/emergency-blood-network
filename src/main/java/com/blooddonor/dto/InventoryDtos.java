package com.blooddonor.dto;

import com.blooddonor.entity.BloodGroup;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    public enum StockLevel { OK, LOW, EMPTY }

    public record StockView(
            BloodGroup bloodGroup,
            String label,
            int units,
            int minUnits,
            boolean autoRequest,
            StockLevel level,
            /* open restock request for this group, if any */
            Long restockRequestId,
            LocalDateTime updatedAt
    ) {
    }

    /** Any field left null keeps its current value. */
    public record UpdateStock(
            @Min(0) @Max(10000) Integer units,
            @Min(0) @Max(1000) Integer minUnits,
            Boolean autoRequest
    ) {
    }

    public record ChangeUnits(@NotNull @Min(1) @Max(1000) Integer units) {
    }

    public record HospitalStock(Long hospitalId, String hospitalName, String city, int totalUnits,
                                int lowGroups, List<StockView> stock) {
    }
}
