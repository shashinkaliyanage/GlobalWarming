package net.porillo.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.CommandHelp;
import co.aikar.commands.annotation.*;
import net.porillo.GlobalWarming;
import net.porillo.database.api.select.GeneralSelection;
import net.porillo.database.api.select.SelectionResult;
import net.porillo.database.queue.AsyncDBQueue;
import net.porillo.database.tables.OffsetTable;
import net.porillo.engine.ClimateEngine;
import net.porillo.engine.models.CarbonIndexModel;
import net.porillo.objects.GPlayer;
import net.porillo.objects.OffsetBounty;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.bukkit.ChatColor.*;

@CommandAlias("globalwarming|gw")
public class GeneralCommands extends BaseCommand {

	private Map<UUID, Long> lastTopped = new HashMap<>();
	private static final UUID untrackedUUID = UUID.fromString("1-1-1-1-1");
	private static final ChatColor[] topHeader = {GOLD, AQUA, LIGHT_PURPLE, AQUA, GOLD, AQUA, LIGHT_PURPLE, AQUA, GOLD,
			AQUA, LIGHT_PURPLE, AQUA, GOLD};

    @Subcommand("score")
    @Description("Get your carbon score")
    @CommandPermission("globalwarming.score")
    public void onScore(GPlayer gPlayer) {
        Player player = Bukkit.getPlayer(gPlayer.getUuid());
        String worldName = player.getWorld().getName();
        int score = gPlayer.getCarbonScore();

        if (ClimateEngine.getInstance().hasClimateEngine(worldName)) {
            double index = ClimateEngine.getInstance().getClimateEngine(worldName).getCarbonIndexModel().getCarbonIndex(score);
            player.sendMessage(LIGHT_PURPLE + "Your carbon footprint index is " + formatIndex(index));
            player.sendMessage(LIGHT_PURPLE + "Your current carbon footprint is " + formatScore(gPlayer.getCarbonScore()));
            player.sendMessage(LIGHT_PURPLE + "Your goal is to keep your index above 5");
        } else {
            player.sendMessage(LIGHT_PURPLE + "Your current overall carbon footprint is " + formatScore(gPlayer.getCarbonScore()));
            player.sendMessage(LIGHT_PURPLE + "Your goal is to keep your index above 5");
        }
    }

	@Subcommand("top")
	@Description("Display the top ten players")
	@CommandPermission("globalwarming.top")
	public void onTop(GPlayer gPlayer) {
		// Prevent players from spamming /gw top (which syncs the database)
		if (lastTopped.containsKey(gPlayer.getUuid())) {
			Long last = lastTopped.get(gPlayer.getUuid());
			long diff = System.currentTimeMillis() - last;

			if (diff < 3000) {
				gPlayer.sendMsg(RED + "Please wait " + YELLOW + (3000 - diff) / 1000 + RED + " seconds to view top again.");
			} else {
				lastTopped.remove(gPlayer.getUuid());
				onTop(gPlayer);
			}
		} else {
			lastTopped.put(gPlayer.getUuid(), System.currentTimeMillis());

			Player player = Bukkit.getPlayer(gPlayer.getUuid());
			String worldName = player.getWorld().getName();

			if (ClimateEngine.getInstance().hasClimateEngine(worldName)) {
				CarbonIndexModel indexModel = ClimateEngine.getInstance().getClimateEngine(worldName).getCarbonIndexModel();
				final String sql = "SELECT uuid,carbonScore FROM players ORDER BY carbonScore ASC LIMIT 10;";

				GeneralSelection selection = new GeneralSelection("players", sql) {
					@Override
					public void onResultArrival(SelectionResult result) throws SQLException {
						if (this.getUuid().equals(result.getUuid())) {
							ResultSet resultSet = result.getResultSet();
							String header = "%s+%s------ %splayer%s ------%s+%s-- %sindex%s --%s+%s-- %sscore%s --%s+";
							String footer = "%s+%s------------------%s+%s-----------%s+%s-----------%s+";
							gPlayer.sendMsg(String.format(header, topHeader));

							String row = DARK_PURPLE + "%d " + WHITE + "%s " + GOLD + "+ %s " + GOLD + "+ %s " + GOLD + "+";
							int i = 1;
							while (resultSet.next()) {
								UUID uuid = UUID.fromString(resultSet.getString(1));
								int score = resultSet.getInt(2);
								double index = indexModel.getCarbonIndex(score);
								String playerName;

								if (uuid.equals(untrackedUUID)) {
									playerName = "Untracked";
								} else {
									playerName = Bukkit.getOfflinePlayer(uuid).getName();
								}

								int pad = i == 10 ? 22 : 23;
								gPlayer.sendMsg(String.format(row, i++, fixed(playerName, pad),
										fixed(formatIndex(index), 13),
										fixed(formatScore(score), 12)));
							}

							gPlayer.sendMsg(String.format(footer, GOLD, AQUA, GOLD, AQUA, GOLD, AQUA, GOLD));
						}
					}
				};
				AsyncDBQueue.getInstance().executeGeneralSelection(selection);
			} else {
				gPlayer.sendMsg(RED + "This world does not have GlobalWarming enabled.");
			}
		}
	}

	public static String fixed(String text, int length) {
		return String.format("%-" + length + "." + length + "s", text);
	}

    private String formatIndex(double index) {
        if (index < 3) {
            return RED + String.format("%1.4f", index);
        } else if (index < 5) {
            return YELLOW + String.format("%1.4f", index);
        } else if (index > 5) {
            return GREEN + String.format("%1.4f", index);
        } else if (index > 7) {
            return DARK_AQUA + String.format("%1.4f", index);
        } else if (index > 9) {
            return DARK_GREEN + String.format("%1.4f", index);
        } else {
            return GRAY + String.format("%1.4f", index);
        }
    }

    // TODO: Make configurable
    // TODO: Add more colors
    private String formatScore(int score) {
        if (score <= 0) {
            return GREEN + String.valueOf(score);
        } else if (score <= 500) {
            return YELLOW + String.valueOf(score);
        } else {
            return RED + String.valueOf(score);
        }
    }

    @Subcommand("bounty")
    @CommandPermission("globalwarming.bounty")
    public class BountyCommand extends BaseCommand {

        @Subcommand("offset")
        @Description("Set tree-planting bounties to reduce carbon footprint")
        @Syntax("[log] [reward]")
        @CommandPermission("globalwarming.bounty.offset")
        public void onBountyOffset(GPlayer gPlayer, String[] args) {
            // Validate input
            Integer logTarget;
            Integer reward;

            if (args.length != 2) {
                gPlayer.sendMsg(RED + "Must specify 2 args");
            }
            try {
                logTarget = Integer.parseInt(args[0]);
                reward = Integer.parseInt(args[1]);

                if (logTarget <= 0 || reward <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException nfe) {
                gPlayer.sendMsg(RED + "Error: <trees> and <reward> must be positive integers");
                return;
            }

            //TODO: Add economy integration
            OffsetBounty bounty = new OffsetBounty();
            bounty.setCreator(gPlayer);
            bounty.setLogBlocksTarget(logTarget);
            bounty.setReward(reward);
        }

        // TODO: When listing bounties, add a clickable chat link to easily start job
        // TODO: Add configurable player max concurrent bounties to prevent bounty hoarding
        @Subcommand("list")
        @Description("Show all current bounties")
        @Syntax("")
        @CommandPermission("globalwarming.bounty.list")
        public void onBounty(GPlayer gPlayer) {
            OffsetTable offsetTable = GlobalWarming.getInstance().getTableManager().getOffsetTable();
            Player player = gPlayer.getPlayer();

            int numBounties = offsetTable.getOffsetList().size();
            gPlayer.sendMsg(GREEN + "Showing " + numBounties + " Tree Planting Bounties");

            // TODO: Paginate if necessary
            for (OffsetBounty bounty : offsetTable.getOffsetList()) {
                if (bounty.isAvailable()) {
                    bounty.showPlayerDetails(player);
                }
            }
        }

    }

    @HelpCommand
    public void onHelp(GPlayer gPlayer, CommandHelp help) {
        help.showHelp();
    }
}