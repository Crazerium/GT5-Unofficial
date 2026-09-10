package gregtech.common.tileentities.machines;

import java.util.Arrays;

import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.util.Platform;
import appeng.util.item.AEFluidStack;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.recipe.check.SimpleCheckRecipeResult;
import gregtech.api.util.GTDataUtils;
import gregtech.api.util.GTUtility;
import gregtech.api.util.shutdown.ShutDownReasonRegistry;

public class MTEHatchInputMELong extends MTEHatchInputME {

    private final long[] initialAmounts = new long[SLOT_COUNT];
    private final long[] remainingAmounts = new long[SLOT_COUNT];

    public MTEHatchInputMELong(int aID, String aName, String aNameRegional) {
        super(aID, false, aName, aNameRegional);
    }

    public MTEHatchInputMELong(String aName, int aTier, String[] aDescription, ITexture[][][] aTextures) {
        super(aName, false, aTier, aDescription, aTextures);
    }

    @Override
    public MetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEHatchInputMELong(mName, mTier, mDescriptionArray, mTextures);
    }

    @Override
    public void setSlotConfig(int index, FluidStack config) {
        super.setSlotConfig(index, config);
        if (initialAmounts != null) initialAmounts[index] = 0L;
        if (remainingAmounts != null) remainingAmounts[index] = 0L;
    }

    @Override
    public void updateInformationSlot(int index) throws GridAccessException {
        Slot slot = GTDataUtils.getIndexSafe(slots, index);
        if (slot == null) {
            initialAmounts[index] = 0L;
            remainingAmounts[index] = 0L;
            return;
        }

        if (!isAllowedToWork()) {
            slot.resetExtracted();
            initialAmounts[index] = 0L;
            remainingAmounts[index] = 0L;
            return;
        }

        IMEMonitor<IAEFluidStack> storage = getProxy().getStorage()
            .getFluidInventory();
        IAEFluidStack request = AEFluidStack.create(slot.config);
        request.setStackSize(Long.MAX_VALUE);
        IAEFluidStack result = storage.extractItems(request, Actionable.SIMULATE, getLongRequestSource());
        long amount = result == null ? 0L : Math.max(0L, result.getStackSize());

        initialAmounts[index] = amount;
        remainingAmounts[index] = amount;
        if (amount <= 0L) {
            slot.resetExtracted();
        } else {
            updateDisplayedAmount(slot, amount);
            slot.extractedAmount = slot.extracted.amount;
        }
    }

    @Override
    public FluidStack drain(ForgeDirection side, FluidStack fluid, int amount, boolean doDrain) {
        if (side != ForgeDirection.UNKNOWN) return null;
        if (!processingRecipe) return super.drain(side, fluid, amount, doDrain);
        if (fluid == null || amount < 0) return null;

        int index = getMatchingSlotIndex(fluid);
        if (index < 0) return null;

        long available = remainingAmounts[index];
        int toDrain = (int) Math.min((long) amount, available);
        if (toDrain <= 0) return null;

        Slot slot = slots[index];
        FluidStack drained = GTUtility.copyAmount(toDrain, slot.config);
        if (doDrain) {
            remainingAmounts[index] -= toDrain;
            updateDisplayedAmount(slot, remainingAmounts[index]);
        }
        return drained;
    }

    public long getLongDisplayAmount(int index) {
        if (index < 0 || index >= slots.length || slots[index] == null) return 0L;
        long amount = processingRecipe ? remainingAmounts[index] : initialAmounts[index];
        return Math.max(0L, amount);
    }

    public long getLongStoredFluidAmount(FluidStack fluid) {
        if (fluid == null || !isAllowedToWork()) return 0L;

        long maximum = 0L;
        for (int i = 0; i < slots.length; i++) {
            Slot slot = slots[i];
            if (slot == null || !GTUtility.areFluidsEqual(slot.config, fluid)) continue;
            if (processingRecipe) {
                maximum = Math.max(maximum, remainingAmounts[i]);
                continue;
            }

            try {
                IMEMonitor<IAEFluidStack> storage = getProxy().getStorage()
                    .getFluidInventory();
                IAEFluidStack request = AEFluidStack.create(slot.config);
                request.setStackSize(Long.MAX_VALUE);
                IAEFluidStack result = storage.extractItems(request, Actionable.SIMULATE, getLongRequestSource());
                if (result != null) maximum = Math.max(maximum, result.getStackSize());
            } catch (GridAccessException ignored) {}
        }
        return maximum;
    }

    @Override
    public CheckRecipeResult endRecipeProcessing(MTEMultiBlockBase controller) {
        CheckRecipeResult checkRecipeResult = CheckRecipeResultRegistry.SUCCESSFUL;

        IMEMonitor<IAEFluidStack> storage;
        IEnergyGrid energy;
        try {
            AENetworkProxy proxy = getProxy();
            if (!proxy.isReady()) proxy.onReady();
            storage = proxy.getStorage()
                .getFluidInventory();
            energy = proxy.getEnergy();
        } catch (GridAccessException e) {
            processingRecipe = false;
            controller.stopMachine(ShutDownReasonRegistry.CRITICAL_NONE);
            return SimpleCheckRecipeResult.ofFailurePersistOnShutdown("stocking_hatch_fail_extraction");
        }

        for (int i = 0; i < SLOT_COUNT; i++) {
            Slot slot = slots[i];
            if (slot == null) continue;

            long trackedRemaining = remainingAmounts[i];
            int expectedDisplayed = (int) Math.min(trackedRemaining, Integer.MAX_VALUE);
            int actualDisplayed = slot.extracted == null ? 0 : Math.max(slot.extracted.amount, 0);
            long directlyConsumed = Math.max(0L, (long) expectedDisplayed - actualDisplayed);
            directlyConsumed = Math.min(directlyConsumed, trackedRemaining);

            long finalRemaining = trackedRemaining - directlyConsumed;
            long toExtract = initialAmounts[i] - finalRemaining;
            remainingAmounts[i] = finalRemaining;
            initialAmounts[i] = finalRemaining;
            slot.extractedAmount = actualDisplayed;
            if (toExtract <= 0L) continue;

            long remainingToExtract = toExtract;
            while (remainingToExtract > 0L) {
                long chunk = Math.min(remainingToExtract, Integer.MAX_VALUE);
                IAEFluidStack request = AEFluidStack.create(slot.config);
                request.setStackSize(chunk);
                IAEFluidStack result = Platform.poweredExtraction(energy, storage, request, getLongRequestSource());

                if (result == null || result.getStackSize() != chunk) {
                    controller.stopMachine(ShutDownReasonRegistry.CRITICAL_NONE);
                    checkRecipeResult = SimpleCheckRecipeResult
                        .ofFailurePersistOnShutdown("stocking_hatch_fail_extraction");
                    break;
                }
                remainingToExtract -= chunk;
            }
        }

        processingRecipe = false;
        return checkRecipeResult;
    }

    @Override
    protected void clearExtractedStacks() {
        super.clearExtractedStacks();
        if (initialAmounts != null) Arrays.fill(initialAmounts, 0L);
        if (remainingAmounts != null) Arrays.fill(remainingAmounts, 0L);
    }

    private int getMatchingSlotIndex(FluidStack fluid) {
        for (int i = 0; i < slots.length; i++) {
            Slot slot = slots[i];
            if (slot == null) continue;
            if (!GTUtility.areFluidsEqual(slot.config, fluid)) continue;
            if (remainingAmounts[i] <= 0L) continue;
            return i;
        }
        return -1;
    }

    private void updateDisplayedAmount(Slot slot, long amount) {
        int displayedAmount = (int) Math.min(Math.max(amount, 0L), Integer.MAX_VALUE);
        if (slot.extracted == null) {
            slot.extracted = GTUtility.copyAmount(displayedAmount, slot.config);
        } else {
            slot.extracted.amount = displayedAmount;
        }
    }

    private BaseActionSource getLongRequestSource() {
        if (requestSource == null) requestSource = new MachineSource((IActionHost) getBaseMetaTileEntity());
        return requestSource;
    }
}
