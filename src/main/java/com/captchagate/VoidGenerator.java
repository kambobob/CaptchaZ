package com.captchagate;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

/** Generates a completely empty (void) world. */
public class VoidGenerator extends ChunkGenerator {

    @Override
    public Location getFixedSpawnLocation(World world, java.util.Random random) {
        return new Location(world, 0.5, 101, 0.5);
    }
}
