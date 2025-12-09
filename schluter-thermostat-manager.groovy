/**
 *  Schluter Ditra-Heat Thermostat Manager App for Hubitat
 *  
 *  This app helps with:
 *  - Authentication (getting session ID)
 *  - Device discovery
 *  - Managing multiple thermostats
 *  
 *  Author: Ilia Gilderman
 *  Date: 2025
 */

definition(
    name: "Schluter Thermostat Manager",
    namespace: "gilderman",
    author: "Ilia Gilderman",
    description: "Integration with Schluter Ditra-Heat floor heating thermostats",
    category: "My Apps",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/gilderman/SchluterThermostat/main/schluter-thermostat-manager.groovy",
    singleInstance: true,
    installOnOpen: true
)

preferences {
    page(name: "mainPage")
    page(name: "authPage")
    page(name: "devicesPage")
}

def mainPage() {
    return dynamicPage(name: "mainPage", title: "Schluter Thermostat Manager", install: true, uninstall: true) {
        section("API Configuration") {
            input name: "apiBaseUrl", type: "text", title: "API Base URL", 
                  defaultValue: "https://schluterditraheat.com/api", required: true
        }
        
        section("Authentication") {
            href "authPage", title: "Authentication Settings", description: "Configure authentication"
            if (state.sessionId) {
                paragraph "Session ID: ${state.sessionId.substring(0, 20)}..."
                input name: "clearSession", type: "button", title: "Clear Session"
            }
        }
        
        section("Device Management") {
            href "devicesPage", title: "Discover Devices", description: "Find and add thermostats"
        }
        
        section("Logging") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

def authPage() {
    return dynamicPage(name: "authPage", title: "Authentication") {
        section("Username/Password Login") {
            paragraph "Enter your Schluter account credentials to authenticate."
            input name: "username", type: "text", title: "Username/Email", required: false
            input name: "password", type: "password", title: "Password", required: false
            
            if (username && password) {
                input name: "loginButton", type: "button", title: "Login with Username/Password"
            }
        }
        
        section("OR - Manual Session ID") {
            paragraph "If you already have a session ID, you can enter it directly."
            input name: "manualSessionId", type: "text", title: "Session ID", required: false
            
            if (manualSessionId) {
                input name: "useManualSession", type: "button", title: "Use This Session ID"
            }
        }
        
        section("Status") {
            if (state.sessionId) {
                paragraph "✓ Authenticated - Session ID: ${state.sessionId.substring(0, 20)}..."
            } else {
                paragraph "Not authenticated. Please log in above."
            }
        }
    }
}

def devicesPage() {
    return dynamicPage(name: "devicesPage", title: "Device Discovery") {
        section("Discover Devices") {
            if (state.locationId) {
                paragraph "Auto-discovered Location ID: ${state.locationId}"
                input name: "discoverDevices", type: "button", title: "Discover Devices"
            } else {
                paragraph "Enter your location ID to discover devices."
            input name: "locationId", type: "number", title: "Location ID", required: false
            
            if (locationId) {
                input name: "discoverDevices", type: "button", title: "Discover Devices"
                }
            }
        }
        
        section("Discovered Devices") {
            if (state.discoveredDevices) {
                state.discoveredDevices.each { device ->
                    if (device) {
                    paragraph """
                        <b>${device.name ?: 'Unknown'}</b>
                    Device ID: ${device.id}
                    Family: ${device.family}
                    """
                    }
                }
            } else {
                paragraph "No devices discovered yet. Authenticate first, then click 'Discover Devices'."
            }
        }
    }
}

def appButtonHandler(btn) {
    switch(btn) {
        case "loginButton":
            loginWithCredentials()
            break
        case "useManualSession":
            state.sessionId = settings.manualSessionId
            log.info "Using manual session ID"
            break
        case "clearSession":
            state.sessionId = null
            log.info "Session cleared"
            break
        case "discoverDevices":
            discoverDevices()
            break
    }
}

def loginWithCredentials() {
    def username = settings.username
    def password = settings.password
    def baseUrl = settings.apiBaseUrl ?: "https://schluterditraheat.com"
    
    if (!username || !password) {
        log.error "Username and password are required"
        return
    }
    
    if (settings.logEnable) log.debug "Logging in with username: ${username}"
    
    // Step 1: Try to login via the web interface
    // The app uses HTTP Basic Auth (schluter:CyEK7zOkM5Ivb1EKrxhK) for the webview
    def basicAuth = ("schluter:CyEK7zOkM5Ivb1EKrxhK").bytes.encodeBase64().toString()
    
    // Login via Schluter API (TESTED AND WORKING!)
    // Handle baseUrl that may or may not include /api
    def baseUrlClean = baseUrl.replaceAll(/\/api\/?$/, '')  // Remove trailing /api if present
    def url = "${baseUrlClean}/api/login"
    
    def params = [
        uri: url,
        headers: [
            "Accept": "application/json",
            "Content-Type": "application/json"
        ],
        body: [
            username: username,
            password: password,
            interface: "schluter"  // REQUIRED!
        ],
        requestContentType: "application/json",
        contentType: "application/json",
        timeout: 30
    ]
    
    if (settings.logEnable) {
        log.debug "Login URL: ${url}"
        log.debug "Login body: username=${username}, interface=schluter"
    }
    
    try {
        httpPostJson(params) { response ->
            if (settings.logEnable) log.debug "Login status: ${response.status}"
            
            if (response.status == 200) {
                def data = response.data
                
                if (settings.logEnable) log.debug "Login response: ${data}"
                
                if (data.session) {
                    state.sessionId = data.session
                    state.userId = data.user?.id
                    state.accountId = data.account?.id
                    
                    // Store credentials for automatic re-authentication
                    if (username) state.username = username
                    if (password) state.password = password
                    
                    log.info "✓ Login successful! Session ID: ${data.session.substring(0, 20)}..."
                    if (data.user?.id) log.info "User ID: ${data.user.id}, Account ID: ${data.account?.id}"
                    
                    updateChildDevicesSession()
                    
                    // Auto-discover location
                    getLocations()
                    
                    // Return to main page
                    return dynamicPage(name: "authPage", title: "Authentication", nextPage: "mainPage") {
                        section {
                            paragraph "✓ Login successful! Redirecting to main page..."
                        }
                    }
                } else if (data.error) {
                    log.error "Login failed: ${data.error.code} - ${data.error.message ?: data.error.data}"
                    if (settings.logEnable) log.debug "Full error: ${data.error}"
                }
            } else {
                log.error "Login HTTP Error ${response.status}"
            }
        }
    } catch (Exception e) {
        log.error "Login exception: ${e.message}"
        if (settings.logEnable) e.printStackTrace()
    }
}

def getLocations() {
    if (!state.sessionId) {
        log.error "No session ID, cannot get locations"
        return
    }
    
    def baseUrl = settings.apiBaseUrl ?: "https://schluterditraheat.com/api"
    def url = "${baseUrl}/locations"
    
    def params = [
        uri: url,
        headers: [
            "Session-Id": state.sessionId
        ],
        contentType: "application/json"
    ]
    
    try {
        httpGet(params) { response ->
            if (response.status == 200) {
                def locations = response.data
                if (locations && locations.size() > 0) {
                    state.locationId = locations[0].id
                    log.info "Found location: ${locations[0].name} (ID: ${locations[0].id})"
                }
            }
        }
    } catch (Exception e) {
        if (settings.logEnable) log.debug "Error getting locations: ${e.message}"
    }
}

def updateChildDevicesSession() {
    // Update child devices with new session
    getChildDevices().each { child ->
        if (state.sessionId) {
            child.updateSetting("sessionId", state.sessionId)
        }
    }
}

def reAuthenticate() {
    // Automatic re-authentication when session expires
    // Use atomic check-and-set to prevent race conditions
    def now = now()
    
    // Check if we're in a rate limit cooldown period
    def lastRateLimit = state.lastRateLimitTime ?: 0
    def cooldownPeriod = 60000 // 60 seconds cooldown after rate limit
    if (lastRateLimit > 0 && (now - lastRateLimit) < cooldownPeriod) {
        def remainingSeconds = Math.round((cooldownPeriod - (now - lastRateLimit)) / 1000)
        log.debug "Rate limit cooldown active. Waiting ${remainingSeconds} more seconds..."
        return false
    }
    
    // Check if re-authentication is already in progress (with timestamp to detect stale locks)
    def reAuthStartTime = state.reAuthenticatingStartTime ?: 0
    if (state.reAuthenticating && reAuthStartTime > 0) {
        // If re-authentication started more than 30 seconds ago, consider it stale and allow retry
        if ((now - reAuthStartTime) < 30000) {
            log.debug "Re-authentication already in progress (started ${Math.round((now - reAuthStartTime)/1000)}s ago), skipping..."
            return false
        } else {
            log.warn "Stale re-authentication lock detected, clearing and retrying..."
            state.reAuthenticating = false
            state.reAuthenticatingStartTime = 0
        }
    }
    
    // CRITICAL: Set flag IMMEDIATELY before any async operations to prevent race conditions
    // This must happen before checking credentials or making any API calls
    state.reAuthenticating = true
    state.reAuthenticatingStartTime = now
    
    def username = state.username ?: settings.username
    def password = state.password ?: settings.password
    
    if (!username || !password) {
        // Clear flag if we can't proceed
        state.reAuthenticating = false
        state.reAuthenticatingStartTime = 0
        log.error "Cannot re-authenticate: credentials not stored. Please log in manually."
        return false
    }
    
    // Proceed with re-authentication immediately
    // The flag is already set, so other calls will be blocked
    return doReAuthenticate()
}

def doReAuthenticate() {
    // Double-check the flag is still set (in case it was cleared)
    if (!state.reAuthenticating) {
        log.debug "Re-authentication was cancelled, aborting..."
        return false
    }
    
    def username = state.username ?: settings.username
    def password = state.password ?: settings.password
    
    if (!username || !password) {
        state.reAuthenticating = false
        state.reAuthenticatingStartTime = 0
        log.error "Cannot re-authenticate: credentials not stored. Please log in manually."
        return false
    }
    
    log.info "Attempting automatic re-authentication..."
    
    def baseUrl = settings.apiBaseUrl ?: "https://schluterditraheat.com"
    def baseUrlClean = baseUrl.replaceAll(/\/api\/?$/, '')
    def url = "${baseUrlClean}/api/login"
    
    def params = [
        uri: url,
        headers: [
            "Accept": "application/json",
            "Content-Type": "application/json"
        ],
        body: [
            username: username,
            password: password,
            interface: "schluter"
        ],
        requestContentType: "application/json",
        contentType: "application/json",
        timeout: 30
    ]
    
    try {
        httpPostJson(params) { response ->
            if (response.status == 200) {
                def data = response.data
                
                if (data.session) {
                    state.sessionId = data.session
                    state.reAuthenticating = false
                    state.reAuthenticatingStartTime = 0
                    state.lastRateLimitTime = 0 // Clear rate limit cooldown on success
                    log.info "✓ Re-authentication successful! New session ID: ${data.session.substring(0, 20)}..."
                    
                    updateChildDevicesSession()
                    return true
                } else if (data.error) {
                    state.reAuthenticating = false
                    state.reAuthenticatingStartTime = 0
                    def errorCode = data.error.code
                    def errorMsg = data.error.message ?: data.error.data
                    
                    // Handle rate limiting errors
                    if (errorCode == "ACCSESSEXC" || errorCode?.contains("429") || errorMsg?.contains("Too Many")) {
                        state.lastRateLimitTime = now()
                        log.warn "Rate limited by API (${errorCode}). Will retry after cooldown period."
                        // Schedule retry after cooldown
                        runIn(60, reAuthenticate)
                        return false
                    }
                    
                    log.error "Re-authentication failed: ${errorCode} - ${errorMsg}"
                    return false
                }
            } else {
                state.reAuthenticating = false
                state.reAuthenticatingStartTime = 0
                log.error "Re-authentication HTTP Error ${response.status}"
                return false
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        state.reAuthenticating = false
        state.reAuthenticatingStartTime = 0
        
        // Handle rate limiting (HTTP 429)
        if (e.statusCode == 429) {
            state.lastRateLimitTime = now()
            log.warn "Rate limited (429). Will retry after cooldown period. Error: ${e.message}"
            // Schedule retry after cooldown
            runIn(60, reAuthenticate)
            return false
        } else {
            log.error "Re-authentication HTTP exception: ${e.statusCode} - ${e.message}"
            return false
        }
    } catch (Exception e) {
        state.reAuthenticating = false
        state.reAuthenticatingStartTime = 0
        log.error "Re-authentication exception: ${e.message}"
        return false
    }
    
    state.reAuthenticating = false
    state.reAuthenticatingStartTime = 0
    return false
}

def discoverDevices() {
    def locationId = state.locationId ?: settings.locationId
    def sessionId = state.sessionId
    def baseUrl = settings.apiBaseUrl ?: "https://schluterditraheat.com/api"
    
    if (!locationId) {
        log.error "No location ID available. Please authenticate first or enter manually."
        return
    }
    
    if (!sessionId) {
        log.error "No session ID. Please authenticate first."
        return
    }
    
    def url = "${baseUrl}/devices?includedLocationChildren=true&location\$id=${locationId}"
    
    if (settings.logEnable) log.debug "Discovering devices from ${url}"
    
    def params = [
        uri: url,
        headers: [
            "Session-Id": sessionId,
            "Accept": "Application/Json",
            "Content-Type": "Application/Json",
            "Access-Control-Allow-Methods": "GET",
            "Cache-Control": "No-Cache"
        ],
        contentType: "application/json"
    ]
    
    try {
        httpGet(params) { response ->
            if (response.status == 200) {
                def data = response.data
                
                if (settings.logEnable) log.debug "Devices response: ${data}"
                
                // Response is an array directly, not wrapped in "devices"
                if (data instanceof List && data.size() > 0) {
                    state.discoveredDevices = data
                    log.info "Discovered ${data.size()} devices"
                    
                    // Create child devices
                    data.each { device ->
                        if (device && device.id) {
                        createChildDevice(device)
                        }
                    }
                } else if (data.error) {
                    log.error "Error discovering devices: ${data.error.message ?: data.error}"
                } else {
                    log.warn "No devices found or unexpected response format"
                }
            } else {
                log.error "Device discovery HTTP Error ${response.status}: ${response.statusText}"
            }
        }
    } catch (Exception e) {
        log.error "Device discovery exception: ${e.message}"
    }
}

def createChildDevice(device) {
    if (!device || !device.id) {
        log.warn "Skipping invalid device: ${device}"
        return
    }
    
    def deviceId = "schluter-thermostat-${device.id}"
    def existingDevice = getChildDevice(deviceId)
    
    // Get device name with prefix [THERMOSTAT]
    def deviceName = device.name ?: "Schluter Thermostat ${device.id}"
    def prefixedName = "[THERMOSTAT] ${deviceName}"
    
    if (!existingDevice) {
        try {
            def child = addChildDevice("gilderman", "Schluter Ditra-Heat Thermostat", deviceId, [
                name: prefixedName,
                label: prefixedName,
                completedSetup: true
            ])
            
            // Store original device name for future updates
            child.updateDataValue("originalDeviceName", deviceName)
            
            // Configure child device
            child.updateSetting("deviceId", device.id)
            child.updateSetting("sessionId", state.sessionId)
            child.updateSetting("apiBaseUrl", settings.apiBaseUrl?.replaceAll(/\/api\/?$/, ''))
            
            log.info "✓ Created: ${prefixedName} (ID: ${device.id})"
        } catch (Exception e) {
            log.error "Error creating ${device.name}: ${e.message}"
        }
    } else {
        // Device exists - update name if device name changed
        def originalName = existingDevice.getDataValue("originalDeviceName") ?: deviceName
        def currentName = existingDevice.label ?: existingDevice.name
        
        // Check if we need to update the name
        def needsNameUpdate = false
        if (originalName != deviceName) {
            // Device name changed in API
            needsNameUpdate = true
            existingDevice.updateDataValue("originalDeviceName", deviceName)
            log.info "Device name changed from '${originalName}' to '${deviceName}'"
        }
        
        def expectedName = "[THERMOSTAT] ${deviceName}"
        if (currentName != expectedName) {
            // Name needs updating
            needsNameUpdate = true
        }
        
        if (needsNameUpdate) {
            existingDevice.setLabel(expectedName)
            log.info "✓ Updated device name to: ${expectedName} (ID: ${device.id})"
        }
        
        log.debug "Device ${device.name} already exists, updating settings"
        // Update settings in case they changed
        existingDevice.updateSetting("sessionId", state.sessionId)
        existingDevice.updateSetting("apiBaseUrl", settings.apiBaseUrl?.replaceAll(/\/api\/?$/, ''))
        existingDevice.updateSetting("deviceId", device.id)
    }
}

def installed() {
    log.info "Schluter Thermostat Manager installed"
    initialize()
}

def updated() {
    log.info "Schluter Thermostat Manager updated"
    initialize()
}

def initialize() {
    // Refresh child devices
    getChildDevices().each { child ->
        if (state.sessionId) {
            child.updateSetting("sessionId", state.sessionId)
        }
        if (settings.apiBaseUrl) {
            child.updateSetting("apiBaseUrl", settings.apiBaseUrl)
        }
        
        // Update device names if original name changed
        def originalName = child.getDataValue("originalDeviceName")
        if (originalName) {
            def expectedName = "[THERMOSTAT] ${originalName}"
            def currentName = child.label ?: child.name
            
            if (currentName != expectedName) {
                child.setLabel(expectedName)
                if (settings.logEnable) log.debug "Updated device name: ${currentName} -> ${expectedName}"
            }
        } else {
            // For devices created before prefix was added, try to extract original name
            def currentName = child.label ?: child.name
            if (currentName && !currentName.startsWith("[THERMOSTAT] ")) {
                // Store the current name as original and add prefix
                child.updateDataValue("originalDeviceName", currentName)
                child.setLabel("[THERMOSTAT] ${currentName}")
                if (settings.logEnable) log.debug "Added prefix to existing device: ${currentName} -> [THERMOSTAT] ${currentName}"
            }
        }
    }
}

def uninstalled() {
    log.info "Schluter Thermostat Manager uninstalled"
    getChildDevices().each { child ->
        deleteChildDevice(child.deviceNetworkId)
    }
}

// Temperature conversion functions
def celsiusToFahrenheit(celsius) {
    return (celsius * 9/5) + 32
}

