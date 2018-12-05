package net.porillo.effect.neutral;

import net.porillo.GlobalWarming;
import net.porillo.effect.ClimateData;
import net.porillo.effect.api.ClimateEffectType;
import net.porillo.effect.api.ListenerClimateEffect;
import net.porillo.engine.ClimateEngine;
import net.porillo.engine.api.AlternateDistribution;
import net.porillo.engine.api.WorldClimateEngine;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntitySpawnEvent;

@ClimateData(type = ClimateEffectType.MOB_SPAWN_RATE, provideModel = false)
public class MobSpawningRate extends ListenerClimateEffect {

    @EventHandler
    public void onMobSpawn(EntitySpawnEvent event) {
        WorldClimateEngine worldEngine = ClimateEngine.getInstance().getClimateEngine(event.getLocation().getWorld().getUID());
        if (worldEngine != null && worldEngine.isEffectEnabled(ClimateEffectType.MOB_SPAWN_RATE)) {
            AlternateDistribution distribution = worldEngine.getEntityFitnessModel().getEntityFitnessMap().get(event.getEntityType());
            if (distribution != null) {
                double chance = distribution.getValue(worldEngine.getTemperature());
                double random = GlobalWarming.getInstance().getRandom().nextDouble();
                if (chance / 100.f <= random) {
                    //Cancel the mob:
                    event.setCancelled(true);

                    //Spawn an alternative, if available:
                    if (!distribution.getAlternate().isEmpty()) {
                        try {
                            //Spawn:
                            Entity entity = event.getLocation().getWorld().spawn(
                                  event.getLocation(),
                                  EntityType.fromName(distribution.getAlternate()).getEntityClass());

                            //Make it a baby, if possible (for fun):
                            if (entity instanceof Ageable) {
                                ((Ageable)entity).setBaby();
                            } else switch (entity.getType()) {
                                case PHANTOM:
                                    ((Phantom)entity).setSize(1);
                                    break;
                                case ZOMBIE:
                                case DROWNED:
                                case HUSK:
                                case PIG_ZOMBIE:
                                case ZOMBIE_VILLAGER:
                                    ((Zombie)entity).setBaby(true);
                                break;
                            }

                        } catch (Exception e) {
                            GlobalWarming.getInstance().getLogger().warning(String.format(
                                  "Error spawning alternate mob: [%s]",
                                  distribution.getAlternate()));
                        }
                    }
                }
            }
        }
    }
}