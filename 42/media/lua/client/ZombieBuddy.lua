local initialized = false

local function checkZombieBuddyInstallation()
    if ZombieBuddy and ZombieBuddy.getVersion then
        print("[ZombieBuddy] ZombieBuddy.getVersion() = " .. ZombieBuddy.getVersion())
        -- ZombieBuddy is properly installed
        return true
    end
    if not ZombieBuddy then
        print("[ZombieBuddy] ZombieBuddy global is nil.")
    else
        print("[ZombieBuddy] ZombieBuddy.getVersion is nil.")
    end
    print("[ZombieBuddy] showing installation notification.")
    
    -- ZombieBuddy is not installed - show notification
    local function showInstallationDialog()
        local core = getCore()
        if not core then
            return
        end
        
        -- Determine the destination directory and screenshot image based on OS
        local dstDir = ""
        local screenshotImage = ""
        if isSystemMacOS() then
            dstDir = "~/Library/Application Support/Steam/steamapps/common/ProjectZomboid/Project Zomboid.app/Contents/Java/"
            screenshotImage = "media/ui/zb_steam_options_osx.png"
        elseif isSystemWindows() then
            -- Windows path - adjust based on your Steam installation
            dstDir = "C:\\Program Files (x86)\\Steam\\steamapps\\common\\ProjectZomboid\\"
            screenshotImage = "media/ui/zb_steam_options_win.png"
        else
            -- Linux path
            dstDir = "~/.steam/steam/steamapps/common/ProjectZomboid/projectzomboid/"
            screenshotImage = "media/ui/zb_steam_options_osx.png"
        end
        
        -- Get the mod directory to find JAR files
        local modInfo = getModInfoByID("ZombieBuddy")
        local modDir = modInfo and modInfo:getDir() or nil
        if not modDir then
            -- Fallback: construct mods directory path
            local docFolder = getMyDocumentFolder()
            if docFolder then
                local sep = getFileSeparator()
                modDir = docFolder .. sep .. "mods" .. sep .. "ZombieBuddy"
            end
        end
        
        local srcDir = modDir .. string.gsub("/libs/", "/", getFileSeparator())
        print("[ZombieBuddy] please copy files from " .. srcDir .. " to " .. dstDir)
        
        -- Determine the command line based on OS
        local cmdLine = ""
        if isSystemWindows() then
            cmdLine = "-agentlib:zbNative --"
        else
            cmdLine = "-javaagent:ZombieBuddy.jar --"
        end
        
        -- Build the file list (zbNative.dll only for Windows)
        local fileList = " - ZombieBuddy.jar <LINE>"
        if isSystemWindows() then
            fileList = fileList .. " - zbNative.dll <LINE>"
        end
        
        -- Get the message template and replace placeholders with actual paths, command line, and file list
        local message = ""
        if isSystemWindows() then
            local releasesURL = "https://github.com/zed-0xff/ZombieBuddy/releases/"
            message = getText("UI_ZB_Install_Windows", releasesURL, srcDir, dstDir, cmdLine)
        else
            message = getText("UI_ZB_Install_Unix", srcDir, dstDir, cmdLine, fileList)
        end
        
        -- Replace screenshot placeholder with actual image path
        message = string.gsub(message, "SCREENSHOT_PLACEHOLDER", screenshotImage)
        
        -- Show modal dialog like the one in media/lua/client/OptionScreens/MainScreen.lua
        local windowWidth = 600 + (core:getOptionFontSizeReal() * 100)
        local windowHeight = 600
        local screenWidth = core:getScreenWidth()
        local screenHeight = core:getScreenHeight()
        local x = (screenWidth - windowWidth) / 2
        local y = screenHeight / 2 - 300
        
        local modal = ISModalRichText:new(x, y, windowWidth, windowHeight, message, false, nil, nil)
        modal:initialise()
        modal.backgroundColor = {r=0, g=0, b=0, a=0.9}
        modal.alwaysOnTop = true
        modal.chatText:paginate()
        modal:setY(screenHeight / 2 - (modal:getHeight() / 2))
        modal:setVisible(true)
        modal:addToUIManager()
    end
    
    showInstallationDialog()
end

local function hookModManager()
    local Mod = zombie.gameStates.ChooseGameInfo.Mod
    local Mod_index = __classmetatables[Mod.class].__index

    -- Allow the mods that are actually available to be re-activated from the mod manager
    local forceActivateMods = ModSelector.Model.forceActivateMods
    ModSelector.Model.forceActivateMods = function(self, modInfo, activate)
        local isAvailable = Mod_index.isAvailable
        Mod_index.isAvailable = ZombieBuddy.isModAvailable
        forceActivateMods(self, modInfo, activate)
        Mod_index.isAvailable = isAvailable
    end

    -- Display a "ZB" badge on mods that depend on ZombieBuddy as a Java mod hint
    local doDrawItem = ModSelector.ModListBox.doDrawItem
    ModSelector.ModListBox.doDrawItem = function(listbox, itemY, item, ...)
        local drawTextureScaled = ISUIElement.drawTextureScaled
        ISUIElement.drawTextureScaled = function(self, texture, x, y, w, h, a, r, g, b)
            if (texture == self.starSetTexture or texture == self.starUnsetTexture)
            and (item.item.requireMods["ZombieBuddy"] or item.item.requireMods["\\ZombieBuddy"])
            then
                local smallFontH = getTextManager():getFontHeight(UIFont.Small)
                self:drawTextRight("ZB", x - (y - itemY), y + (h - smallFontH) / 2, 0.5, 0.7, 0.45, 1.0, UIFont.Small)
            end
            return drawTextureScaled(self, texture, x, y, w, h, a, r, g, b)
        end
        local ret = {doDrawItem(listbox, itemY, item, ...)}
        ISUIElement.drawTextureScaled = drawTextureScaled
        return unpack(ret)
    end
end

local function onMainMenuEnter()
    if initialized then
        return
    end
    initialized = true

    if checkZombieBuddyInstallation() then
        hookModManager()
    end
end

-- Run the check when on main menu
Events.OnMainMenuEnter.Add(onMainMenuEnter)
