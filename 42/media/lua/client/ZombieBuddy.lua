local hasShownNotification = false

local function checkZombieBuddyInstallation()
    -- Only check once
    if hasShownNotification then
        return
    end
    
    local versionString = getCore():getVersion()
    if not versionString then
        return
    end
    
    -- Check if version contains [ZB] substring
    -- If it does, ZombieBuddy is installed, so we don't need to show notification
    if string.find(versionString, "[ZB]", 1, true) then
        print("[ZombieBuddy] Detected [ZB] in version string, assuming installation is correct.")
        -- ZombieBuddy is properly installed
        return
    end
    print("[ZombieBuddy] showing installation notification.")
    
    -- ZombieBuddy is not installed - show notification
    hasShownNotification = true
    
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
            dstDir = "~/.steam/steam/steamapps/common/ProjectZomboid/projectzomboid/java/"
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
        
        local srcDir = modDir .. string.gsub("/42/media/java/build/libs/", "/", getFileSeparator())
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
            fileList = fileList .. " - zbNative.dlz (rename to zbNative.dll) <LINE>"
        end
        
        -- Get the message template and replace placeholders with actual paths, command line, and file list
        local message = getText("UI_ZB_Install", srcDir, dstDir, cmdLine, fileList)
        
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

-- Run the check when on main menu
Events.OnMainMenuEnter.Add(checkZombieBuddyInstallation)

