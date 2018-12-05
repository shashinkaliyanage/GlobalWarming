package net.porillo.util;

import lombok.Getter;
import net.porillo.GlobalWarming;
import net.porillo.commands.GeneralCommands;
import net.porillo.config.Lang;
import net.porillo.database.tables.WorldTable;
import net.porillo.effect.EffectEngine;
import net.porillo.effect.api.ClimateEffectType;
import net.porillo.effect.negative.SeaLevelRise;
import net.porillo.effect.negative.formation.IceForm;
import net.porillo.effect.negative.formation.SnowForm;
import net.porillo.effect.neutral.FarmYield;
import net.porillo.effect.neutral.Weather;
import net.porillo.engine.ClimateEngine;
import net.porillo.engine.api.Distribution;
import net.porillo.engine.api.WorldClimateEngine;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Maintain one periodic notification-message per world (uses the scoreboard's worlds)
 *  - One message per player
 *  - Messages are based on each world's temperature
 *  - Supports effect-model modifications
 */
public class CO2Notifications {
    private enum TemperatureRange {LOW, AVERAGE, HIGH}

    @Getter private Map<UUID, BossBar> bossBars;
    private static final long NOTIFICATION_INTERVAL_TICKS = GlobalWarming.getInstance().getConf().getNotificationInterval();
    private static final long NOTIFICATION_DURATION_TICKS = GlobalWarming.getInstance().getConf().getNotificationDuration();

    public CO2Notifications() {
        bossBars = new HashMap<>();
        showPlayerNotifications();
    }

    private void showPlayerNotifications() {
        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(
              GlobalWarming.getInstance(),
              () -> {
                  //Create notification for all players, grouped by associated-world
                  synchronized (this) {
                      bossBars.clear();
                      GScoreboard scoreboard = GlobalWarming.getInstance().getScoreboard();
                      for (UUID worldId : scoreboard.getScoreboards().keySet()) {
                          World world = Bukkit.getWorld(worldId);
                          if (world != null) {
                              BossBar bossBar = Bukkit.createBossBar(
                                    getNotificationMessage(worldId),
                                    BarColor.WHITE,
                                    BarStyle.SOLID);

                              bossBars.put(worldId, bossBar);
                              for (Player player : world.getPlayers()) {
                                  bossBar.addPlayer(player);
                              }
                          }
                      }
                  }

                  //Hide the notification after some time:
                  Bukkit.getScheduler().scheduleAsyncDelayedTask(
                        GlobalWarming.getInstance(),
                        () -> {
                            synchronized (this) {
                                for (BossBar bossBar : bossBars.values()) {
                                    bossBar.removeAll();
                                }
                            }
                        },
                        NOTIFICATION_DURATION_TICKS);
              }, 0L, NOTIFICATION_INTERVAL_TICKS);
    }

    private String getNotificationMessage(UUID worldId) {
        String message = Lang.ENGINE_DISABLED.get();
        WorldClimateEngine climateEngine = ClimateEngine.getInstance().getClimateEngine(worldId);
        if (climateEngine != null && climateEngine.isEnabled()) {
            message = getTemperatureGuidance(worldId, climateEngine.getTemperature());
        }

        return message;
    }

    private String getTemperatureGuidance(UUID worldId, double temperature) {
        //Get the temperature range:
        String message = "";
        String optional = "";
        final TemperatureRange range;
        if (temperature < WorldTable.LOW_TEMPERATURE_UBOUND) {
            range = TemperatureRange.LOW;
        } else if (temperature < WorldTable.HIGH_TEMPERATURE_LBOUND) {
            range = TemperatureRange.AVERAGE;
        } else {
            range = TemperatureRange.HIGH;
        }

        try {
            //Get a message based on current conditions:
            final double random = GlobalWarming.getInstance().getRandom().nextDouble();
            final WorldClimateEngine worldClimateEngine = ClimateEngine.getInstance().getClimateEngine(worldId);
            if (worldClimateEngine != null) {
                if (worldClimateEngine.isEffectEnabled(ClimateEffectType.FARM_YIELD) && random < 0.1) {
                    //Farm yields (with random materials):
                    final FarmYield farmYield = EffectEngine.getInstance().getEffect(FarmYield.class, ClimateEffectType.FARM_YIELD);
                    final List<Material> keys = new ArrayList<>(farmYield.getCropDistribution().keySet());
                    final Material randomMaterial = keys.get(GlobalWarming.getInstance().getRandom().nextInt(keys.size()));
                    final Distribution distribution = farmYield.getCropDistribution().get(randomMaterial);
                    final double farmYieldFitness = distribution.getValue(temperature);
                    final double normalFarmYieldFitness = distribution.getValue(WorldTable.DEFAULT_WORLD_TEMPERATURE);
                    optional = randomMaterial.toString().toLowerCase().replace("_", "");
                    message = getMessage(
                          farmYieldFitness < normalFarmYieldFitness,
                          range,
                          Lang.NOTIFICATION_FARM_LOW,
                          Lang.NOTIFICATION_FARM_OK,
                          Lang.NOTIFICATION_FARM_HIGH);
                } else if (worldClimateEngine.isEffectEnabled(ClimateEffectType.ICE_FORMATION) && random < 0.2) {
                    //Ice:
                    final Distribution distribution = EffectEngine.getInstance().getEffect(IceForm.class, ClimateEffectType.ICE_FORMATION).getHeightMap();
                    final double iceFitness = distribution.getValue(temperature);
                    final double normalIceFitness = distribution.getValue(WorldTable.DEFAULT_WORLD_TEMPERATURE);
                    message = getMessage(
                          iceFitness != normalIceFitness,
                          range,
                          Lang.NOTIFICATION_ICE_LOW,
                          Lang.NOTIFICATION_ICE_OK,
                          Lang.NOTIFICATION_ICE_HIGH);
                } else if (worldClimateEngine.isEffectEnabled(ClimateEffectType.MOB_SPAWN_RATE) && random < 0.3) {
                    //Mob (with random entities):
                    final List<EntityType> keys = new ArrayList<>(worldClimateEngine.getEntityFitnessModel().getEntityFitnessMap().keySet());
                    final EntityType randomEntity = keys.get(GlobalWarming.getInstance().getRandom().nextInt(keys.size()));
                    final Distribution distribution = worldClimateEngine.getEntityFitnessModel().getEntityFitnessMap().get(randomEntity);
                    final double mobFitness = distribution.getValue(temperature);
                    final double normalMobFitness = distribution.getValue(WorldTable.DEFAULT_WORLD_TEMPERATURE);
                    optional = randomEntity.toString().toLowerCase().replace("_", "");
                    message = getMessage(
                          mobFitness < normalMobFitness,
                          range,
                          Lang.NOTIFICATION_MOB_LOW,
                          Lang.NOTIFICATION_MOB_OK,
                          Lang.NOTIFICATION_MOB_HIGH);
                } else if (worldClimateEngine.isEffectEnabled(ClimateEffectType.WEATHER) && random < 0.4) {
                    //Weather (storms):
                    final Weather weather = EffectEngine.getInstance().getEffect(Weather.class, ClimateEffectType.WEATHER);
                    final Distribution distribution = weather.getWeatherDistribution().get(Weather.WeatherData.STORM);
                    final double weatherFitness = distribution.getValue(temperature);
                    final double normalWeatherFitness = distribution.getValue(WorldTable.DEFAULT_WORLD_TEMPERATURE);
                    message = getMessage(
                          weatherFitness > normalWeatherFitness,
                          range,
                          Lang.NOTIFICATION_WEATHER_LOW,
                          Lang.NOTIFICATION_WEATHER_OK,
                          Lang.NOTIFICATION_WEATHER_HIGH);
                } else if (worldClimateEngine.isEffectEnabled(ClimateEffectType.SEA_LEVEL_RISE) && random < 0.5) {
                    //Sea-level messages:
                    // Note: sea-level deltas are 0+ (can only rise)
                    final String temperatureKey = String.valueOf((int) temperature);
                    final String normalTemperatureKey = String.valueOf((int) WorldTable.DEFAULT_WORLD_TEMPERATURE);
                    final SeaLevelRise seaLevelRise = EffectEngine.getInstance().getEffect(SeaLevelRise.class, ClimateEffectType.SEA_LEVEL_RISE);
                    final int seaLevelDelta = seaLevelRise.getJsonModel().get(temperatureKey).getAsInt();
                    final int normalSeaLevelDelta = seaLevelRise.getJsonModel().get(normalTemperatureKey).getAsInt();
                    message = getMessage(
                          seaLevelDelta > normalSeaLevelDelta,
                          range,
                          Lang.NOTIFICATION_SEALEVEL_LOW,
                          Lang.NOTIFICATION_SEALEVEL_OK,
                          Lang.NOTIFICATION_SEALEVEL_HIGH);
                } else if (worldClimateEngine.isEffectEnabled(ClimateEffectType.SNOW_FORMATION) && random < 0.6) {
                    //Snow messages:
                    final Distribution distribution = EffectEngine.getInstance().getEffect(SnowForm.class, ClimateEffectType.SNOW_FORMATION).getHeightMap();
                    final double snowFitness = distribution.getValue(temperature);
                    final double normalSnowFitness = distribution.getValue(WorldTable.DEFAULT_WORLD_TEMPERATURE);
                    message = getMessage(
                          snowFitness != normalSnowFitness,
                          range,
                          Lang.NOTIFICATION_SNOW_LOW,
                          Lang.NOTIFICATION_SNOW_OK,
                          Lang.NOTIFICATION_SNOW_HIGH);
                }
            }
        } finally {
            //Default messages:
            if (message.isEmpty()) {
                message = getMessage(
                      range != TemperatureRange.AVERAGE,
                      range,
                      Lang.NOTIFICATION_DEFAULT_LOW,
                      Lang.NOTIFICATION_DEFAULT_OK,
                      Lang.NOTIFICATION_DEFAULT_HIGH);
            }
        }

        return String.format(
              message,
              GeneralCommands.getTemperatureColor(temperature),
              optional);
    }

    private static String getMessage(boolean isEffectActive, TemperatureRange range, Lang tooLow, Lang ok, Lang tooHigh) {
        String message = ok.get();
        if (isEffectActive) {
            switch (range) {
                case LOW:
                    message = tooLow.get();
                    break;
                case HIGH:
                    message = tooHigh.get();
                    break;
            }
        }

        return message;
    }
}