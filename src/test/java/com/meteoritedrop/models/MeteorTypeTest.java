package com.meteoritedrop.models;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MeteorTypeTest {
    @Test
    void craterDimensionsStayWithinConfiguredSafetyBounds() {
        MeteorType type = new MeteorType("test", "Test", Material.CHEST, Material.MAGMA_BLOCK,
                Material.STONE, Material.COBBLESTONE, Material.MAGMA_BLOCK,
                Integer.MAX_VALUE, -10, 30, 5, List.of());

        assertEquals(MeteorType.MAX_CRATER_RADIUS, type.getCraterRadius());
        assertEquals(0, type.getCraterDepth());
        type.setCraterDepth(Integer.MAX_VALUE);
        assertEquals(MeteorType.MAX_CRATER_DEPTH, type.getCraterDepth());
    }
}
