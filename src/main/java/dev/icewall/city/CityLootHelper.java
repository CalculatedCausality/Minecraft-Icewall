package dev.icewall.city;

import dev.icewall.city.CityDomain.BuildingType;
import dev.icewall.city.CityDomain.CityDistrict;
import dev.icewall.city.CityDomain.CityProfile;
import dev.icewall.config.IceWallConfig;
import java.util.List;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.resources.ResourceKey;

/**
 * Loot stocking, resident spawning, and written-book generation for cities.
 *
 * This class is stateless; all relevant context is passed in per-call.
 */
public final class CityLootHelper {

    // -----------------------------------------------------------------------
    // Container stocking
    // -----------------------------------------------------------------------

    public void stockCityCache(ServerLevel world, BlockPos pos, CityProfile profile) {
        BaseContainerBlockEntity container = container(world, pos);
        if (container == null) return;
        ItemStack[] identity = switch (profile.district()) {
            case FORGEWARD   -> new ItemStack[] { new ItemStack(Items.IRON_INGOT, 12), new ItemStack(Items.COAL, 16), new ItemStack(Items.ANVIL, 1) };
            case GARDENWARD  -> new ItemStack[] { new ItemStack(Items.WHEAT_SEEDS, 16), new ItemStack(Items.BREAD, 12), new ItemStack(Items.BONE_MEAL, 12) };
            case ARCHIVEWARD -> new ItemStack[] { new ItemStack(Items.BOOK, 8), new ItemStack(Items.PAPER, 24), new ItemStack(Items.COMPASS, 1) };
            case MARKETWARD  -> new ItemStack[] { new ItemStack(Items.EMERALD, 6), new ItemStack(Items.BREAD, 8), new ItemStack(Items.LEATHER, 10) };
            case CITADEL     -> new ItemStack[] { new ItemStack(Items.ARROW, 32), new ItemStack(Items.IRON_SWORD, 1), new ItemStack(Items.SHIELD, 1) };
        };
        for (int i = 0; i < identity.length && i < container.getContainerSize(); i++) container.setItem(i, identity[i].copy());
        if (identity.length < container.getContainerSize()) container.setItem(identity.length, writtenBulletinBook(profile, 0));
    }

    public void stockEvacuationCache(ServerLevel world, BlockPos pos, CityProfile profile) {
        BaseContainerBlockEntity container = container(world, pos);
        if (container == null) return;
        ItemStack[] supplies = {
            new ItemStack(Items.BREAD, 12),
            new ItemStack(Items.COOKED_BEEF, 6),
            new ItemStack(Items.COAL, 16),
            new ItemStack(Items.TORCH, 24),
            new ItemStack(Items.MINECART, 1),
            new ItemStack(Items.RAIL, 24),
            specialtyLoot(BuildingType.BUNKHOUSE, profile.district(), new Random(profile.name().hashCode() ^ 0xEAC0A7E))
        };
        for (int i = 0; i < supplies.length && i < container.getContainerSize(); i++) container.setItem(i, supplies[i].copy());
    }

    public void stockFrozenCache(ServerLevel world, BlockPos pos, CityProfile profile) {
        BaseContainerBlockEntity container = container(world, pos);
        if (container == null) return;
        ItemStack[] loot = {
            new ItemStack(Items.BLUE_ICE, 8),
            new ItemStack(Items.PACKED_ICE, 12),
            new ItemStack(Items.SNOWBALL, 16),
            new ItemStack(Items.BONE, 6),
            new ItemStack(Items.ARROW, 12),
            new ItemStack(Items.BREAD, 3),
            specialtyLoot(BuildingType.WAREHOUSE, profile.district(), new Random(profile.name().hashCode()))
        };
        for (int i = 0; i < loot.length && i < container.getContainerSize(); i++) container.setItem(i, loot[i].copy());
    }

    public void stockContainer(ServerLevel world, BlockPos pos, BuildingType type, CityDistrict district, Random random) {
        BaseContainerBlockEntity container = container(world, pos);
        if (container == null) return;
        ItemStack[] base = {
            new ItemStack(Items.BREAD, 6), new ItemStack(Items.COAL, 8), new ItemStack(Items.IRON_INGOT, 4),
            new ItemStack(Items.TORCH, 16), new ItemStack(Items.ARROW, 12), new ItemStack(Items.OAK_PLANKS, 12),
            new ItemStack(Items.PAPER, 8), new ItemStack(Items.EMERALD, 2)
        };
        for (int i = 0; i < 3 && i < container.getContainerSize(); i++)
            container.setItem(random.nextInt(container.getContainerSize()), base[random.nextInt(base.length)].copy());
        container.setItem(random.nextInt(container.getContainerSize()), specialtyLoot(type, district, random));
    }

    public ItemStack specialtyLoot(BuildingType type, CityDistrict district, Random random) {
        if (random.nextBoolean()) {
            return switch (type) {
                case WATCHTOWER -> new ItemStack(Items.ARROW, 16);
                case GREENHOUSE -> new ItemStack(Items.WHEAT_SEEDS, 12);
                case FORGE      -> new ItemStack(Items.IRON_INGOT, 6);
                case ARCHIVE    -> new ItemStack(Items.BOOK, 4);
                case BUNKHOUSE  -> new ItemStack(Items.BREAD, 6);
                case MARKET     -> new ItemStack(Items.EMERALD, 3);
                case CHAPEL     -> new ItemStack(Items.BLUE_ICE, 4);
                case WAREHOUSE  -> new ItemStack(Items.OAK_PLANKS, 24);
            };
        }
        return switch (district) {
            case FORGEWARD   -> new ItemStack(Items.COAL, 12);
            case GARDENWARD  -> new ItemStack(Items.BONE_MEAL, 8);
            case ARCHIVEWARD -> new ItemStack(Items.PAPER, 12);
            case MARKETWARD  -> new ItemStack(Items.EMERALD, 2);
            case CITADEL     -> new ItemStack(Items.SHIELD, 1);
        };
    }

    // -----------------------------------------------------------------------
    // Written books
    // -----------------------------------------------------------------------

    public ItemStack writtenBulletinBook(CityProfile profile, int wallZ) {
        String author = profile.name() + " Council";
        String title  = profile.name() + " Bulletin";
        String page   = bulletinPage(profile, wallZ);
        Filterable<String> filteredTitle = Filterable.passThrough(title);
        Filterable<Component> filteredPage = Filterable.passThrough(Component.literal(page));
        WrittenBookContent content = new WrittenBookContent(filteredTitle, author, 0, List.of(filteredPage), true);
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        return book;
    }

    private String bulletinPage(CityProfile profile, int wallZ) {
        String header = "=== " + profile.name() + " ===\n"
                + CityDomain.districtLabel(profile.district()) + " / " + CityDomain.styleLabel(profile.style()) + "\n\n";
        String body = switch (profile.district()) {
            case FORGEWARD ->
                "Crew assignments:\n- Stoke the blast furnace\n- Stockpile coal at the south gate\n"
                + "- Distribute tools to all evacuation packs\n\nFrost advance expected in " + wallZ + " blocks.";
            case GARDENWARD ->
                "Harvest roster:\n- Strip the wheat beds first\n- Seal seed barrels for transport\n"
                + "- Bone meal stores move with the caravan\n\nThe wall does not wait.";
            case ARCHIVEWARD ->
                "Preservation orders:\n- Copy the navigation charts\n- Wrap books in oilskin\n"
                + "- Send one compass south with each convoy\n\nKnowledge survives if we carry it.";
            case MARKETWARD ->
                "Trade manifest:\n- Sort emerald reserves by weight\n- Mark perishables for first-cart loading\n"
                + "- Any unclaimed barrels go to the gate cache\n\nPay what you owe. Leave what you cannot carry.";
            case CITADEL ->
                "Defence orders:\n- Two archers on each gate tower\n- Seal the north breach with iron bars\n"
                + "- Sound the bell if raiders approach the platform\n\nHold until the last cart rolls.";
        };
        return header + body;
    }

    // -----------------------------------------------------------------------
    // Resident spawning
    // -----------------------------------------------------------------------

    public void spawnResident(ServerLevel world, int x, int baseY, int z,
            BuildingType type, CityProfile profile, Random random) {
        if (random.nextInt(IceWallConfig.CITY_RESIDENT_CHANCE) != 0) return;
        Villager villager = EntityType.VILLAGER.create(world, EntitySpawnReason.NATURAL);
        if (villager == null) return;
        villager.teleportTo(x + 0.5, baseY, z + 0.5);
        villager.setVillagerData(villager.getVillagerData().withProfession(world.registryAccess(), professionFor(type)));
        villager.setCustomName(Component.literal(residentName(type, profile)).withStyle(ChatFormatting.GOLD));
        villager.setCustomNameVisible(false);
        villager.setPersistenceRequired();
        world.addFreshEntity(villager);
    }

    public void spawnAftermathPatrol(ServerLevel world, int x, int y, int z, CityProfile profile, Random random) {
        if (random.nextInt(IceWallConfig.CITY_RUIN_PATROL_CHANCE) != 0) return;
        var stray = EntityType.STRAY.create(world, EntitySpawnReason.NATURAL);
        if (stray == null) return;
        stray.teleportTo(x + 0.5, y, z + 0.5);
        stray.setCustomName(Component.literal(profile.name() + " Ruin Patrol").withStyle(ChatFormatting.DARK_AQUA));
        stray.setCustomNameVisible(false);
        stray.setPersistenceRequired();
        world.addFreshEntity(stray);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private static BaseContainerBlockEntity container(ServerLevel world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        return be instanceof BaseContainerBlockEntity c ? c : null;
    }

    private static ResourceKey<VillagerProfession> professionFor(BuildingType type) {
        return switch (type) {
            case WATCHTOWER -> VillagerProfession.FLETCHER;
            case GREENHOUSE -> VillagerProfession.FARMER;
            case FORGE      -> VillagerProfession.TOOLSMITH;
            case ARCHIVE    -> VillagerProfession.LIBRARIAN;
            case BUNKHOUSE  -> VillagerProfession.LEATHERWORKER;
            case MARKET     -> VillagerProfession.CARTOGRAPHER;
            case CHAPEL     -> VillagerProfession.CLERIC;
            case WAREHOUSE  -> VillagerProfession.MASON;
        };
    }

    private static String residentName(BuildingType type, CityProfile profile) {
        String role = switch (type) {
            case WATCHTOWER -> "Lookout";
            case GREENHOUSE -> "Gardener";
            case FORGE      -> "Smith";
            case ARCHIVE    -> "Archivist";
            case BUNKHOUSE  -> "Quartermaster";
            case MARKET     -> "Trader";
            case CHAPEL     -> "Keeper";
            case WAREHOUSE  -> "Mason";
        };
        return profile.name() + " " + role;
    }
}
