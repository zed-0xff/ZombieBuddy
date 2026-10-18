if not PZAPI or not PZAPI.ModOptions then return end

local options = PZAPI.ModOptions:create("ZombieBuddy", "ZombieBuddy")

local config = {
    watermarkOpacity   = options:addSlider( "watermarkOpacity",    "UI_ZB_WatermarkOpacity", 0.0, 1.0, 0.05, 0.4 ),
    suppressSandboxLog = options:addTickBox( "suppressSandboxLog", "UI_ZB_SuppressSandboxLog", false, "UI_ZB_SuppressSandboxLog_desc" ),
    autoFixModOrder           = options:addTickBox( "autoFixModOrder",           "UI_ZB_AutoFixModOrder",           true, "UI_ZB_AutoFixModOrder_desc" ),
    fixApprovalDialogCursor   = options:addTickBox( "fixApprovalDialogCursor",   "UI_ZB_FixApprovalDialogCursor",   true, "UI_ZB_FixApprovalDialogCursor_desc" ),
    fixMacOSRetina            = options:addTickBox( "fixMacOSRetina",            "UI_ZB_FixMacOSRetina",            false, "UI_ZB_FixMacOSRetina_desc" ),
    stealthMode               = options:addTickBox( "stealthMode",               "UI_ZB_StealthMode",               false, "UI_ZB_StealthMode_desc" ),
}

local function onChangeWatermarkOpacity(self, value)
    if ZombieBuddy and ZombieBuddy.Watermark and ZombieBuddy.Watermark.setAlpha then
        ZombieBuddy.Watermark.setAlpha(value)
    end
end

config.watermarkOpacity.onChange = onChangeWatermarkOpacity

-- ---------------------------------------------------------------------------
-- Suppress sandbox options logging (GameLoadingState.exit)
-- ---------------------------------------------------------------------------

local function applySettings()
    if ZombieBuddy then
        if ZombieBuddy.setAutoFixModOrder then
            ZombieBuddy.setAutoFixModOrder(config.autoFixModOrder:getValue())
        end
        if ZombieBuddy.setFixApprovalDialogCursor then
            ZombieBuddy.setFixApprovalDialogCursor(config.fixApprovalDialogCursor:getValue())
        end
        if ZombieBuddy.setFixMacOSRetina then
            ZombieBuddy.setFixMacOSRetina(config.fixMacOSRetina:getValue())
        end
        if ZombieBuddy.setStealthMode then
            ZombieBuddy.setStealthMode(config.stealthMode:getValue())
        end
        if ZombieBuddy.Watermark and ZombieBuddy.Watermark.setAlpha then
            ZombieBuddy.Watermark.setAlpha(config.watermarkOpacity:getValue())
        end
        if ZombieBuddy.Patches and ZombieBuddy.Patches.GameLoadingState and ZombieBuddy.Patches.GameLoadingState.setSuppressSandboxLog then
            ZombieBuddy.Patches.GameLoadingState.setSuppressSandboxLog(config.suppressSandboxLog:getValue())
        end
    end
end

options.apply = applySettings

Events.OnMainMenuEnter.Add(applySettings) -- apply settings at game launch
