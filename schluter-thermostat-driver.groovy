/**
 *  Schluter Ditra-Heat Thermostat Driver for Hubitat
 *  
 *  For Schluter Ditra-Heat floor heating thermostats
 *  These are OEM Sinope devices using Neviweb cloud platform
 *  
 *  Author: Ilia Gilderman
 *  Date: 2025
 */

metadata {
    definition(
        name: "Schluter Ditra-Heat Thermostat", 
        namespace: "gilderman", 
        author: "Ilia Gilderman",
        description: "Schluter Ditra-Heat floor heating control",
        category: "My Apps",
        importUrl: "https://raw.githubusercontent.com/gilderman/SchluterThermostat/main/schluter-thermostat-driver.groovy"
    ) {
        capability "Thermostat"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatMode"
        capability "ThermostatOperatingState"
        capability "TemperatureMeasurement"
        capability "Sensor"
        capability "Refresh"
        
        command "setHeatingSetpoint", ["number"]
        command "setThermostatMode", ["string"]
        command "updateTemperature", ["number"]
        
        attribute "thermostatMode", "enum", ["off", "heat", "auto"]
        attribute "supportedThermostatModes", "ENUM", ["off", "heat", "auto"]
        attribute "heatingOutput", "number"
        attribute "airFloorMode", "string"
        attribute "lastUpdate", "string"
        attribute "connectionStatus", "string"
    }

    preferences {
        input name: "apiBaseUrl", type: "text", title: "API Base URL", 
              defaultValue: "https://schluterditraheat.com/api", required: true
        input name: "sessionId", type: "text", title: "Session ID", 
              description: "Automatically set by Manager app, or enter manually"
        input name: "deviceId", type: "number", title: "Device ID", 
              description: "Device ID from devices list", required: true
        input name: "pollInterval", type: "number", title: "Poll Interval (minutes)", 
              defaultValue: 1, required: true
        input name: "autoOffHours", type: "number", title: "Auto-Off Time (hours)", 
              description: "Turn off after this many hours (0 to disable)", 
              defaultValue: 0, range: "0..24"
        input name: "logEnable", type: "bool", title: "Enable debug logging", 
              defaultValue: false
    }
}

def installed() {
    log.info "Schluter Thermostat Driver installed"
    initialize()
}

def updated() {
    log.info "Schluter Thermostat Driver updated"
    initialize()
    
    // Reschedule auto-off if device is currently on
    def currentMode = device.currentValue("thermostatMode")
    if (currentMode == "heat" || currentMode == "auto") {
        scheduleAutoOff()
    } else {
        cancelAutoOff()
    }
    
    refresh()
}

def initialize() {
    state.deviceId = settings.deviceId
    state.lastPoll = 0
    // Don't cache session in state - always use settings
    
    if (settings.pollInterval) {
        unschedule()
        schedule("0 */${settings.pollInterval} * * * ?", poll)
    }
    
    // Set default setpoint to 85°F if not already set
    if (device.currentValue("heatingSetpoint") == null) {
        sendEvent(name: "heatingSetpoint", value: 85, unit: "F")
    }
    
    // Set default thermostat mode to "off" if not already set
    if (device.currentValue("thermostatMode") == null) {
        sendEvent(name: "thermostatMode", value: "off")
    }
    
    // Initialize supported thermostat modes
    sendEvent(name: "supportedThermostatModes", value: ["off", "heat", "auto"])
    
    // Initialize cooling/fan attributes to null (heating-only device)
    // These are part of the Thermostat capability but not used
    if (device.currentValue("coolingSetpoint") == null) {
        sendEvent(name: "coolingSetpoint", value: null, unit: "F")
    }
    if (device.currentValue("thermostatFanMode") == null) {
        sendEvent(name: "thermostatFanMode", value: null)
    }
    
    log.info "Initialized with device ID: ${state.deviceId}, session: ${settings.sessionId?.substring(0,20)}..."
}

def refresh() {
    if (settings.logEnable) log.debug "Refreshing device state"
    
    if (!state.sessionId && !settings.sessionId) {
        log.error "No session ID configured. Please authenticate in the Manager app."
        sendEvent(name: "connectionStatus", value: "disconnected")
        return
    }
    
    // Get all device attributes in a single API call
    getAllDeviceAttributes()
}

def poll() {
    refresh()
}

def getAllDeviceAttributes() {
    def deviceId = state.deviceId ?: settings.deviceId
    // Always prefer settings.sessionId (from Manager) over state
    def sessionId = settings.sessionId ?: state.sessionId
    def baseUrl = settings.apiBaseUrl ?: "https://schluterditraheat.com"
    
    if (!deviceId || !sessionId) {
        log.error "Missing deviceId or sessionId"
        return
    }
    
    if (settings.logEnable) log.debug "Using session: ${sessionId?.substring(0,20)}..."
    
    // Request all attributes at once
    def attributes = "roomTemperatureDisplay,roomSetpoint,outputPercentDisplay,airFloorMode,setpointMode"
    def baseUrlClean = baseUrl.replaceAll(/\/api\/?$/, '')  // Remove /api if present
    def url = "${baseUrlClean}/api/device/${deviceId}/attribute?attributes=${attributes}"
    
    if (settings.logEnable) log.debug "Getting all attributes from ${url}"
    
    def params = [
        uri: url,
        headers: [
            "Session-Id": sessionId,
            "Accept": "Application/Json",
            "Content-Type": "Application/Json",
            "Cache-Control": "No-Cache"
        ],
        contentType: "application/json"
    ]
    
    try {
        httpGet(params) { response ->
            try {
                if (settings.logEnable) log.debug "Response status: ${response.status}"
                
                def data = response.data
                if (settings.logEnable) log.debug "Response data: ${data}"
                
                if (!data || data == [:]) {
                    if (settings.logEnable) log.debug "Empty response"
                    return
                }
                
                // Check for session expiration first
                if (data.error?.code == "USRSESSEXP") {
                    sendEvent(name: "connectionStatus", value: "session_expired")
                    // Throttle: only handle expiration once per minute
                    def lastHandled = state.lastSessionExpirationHandled ?: 0
                    def now = now()
                    if ((now - lastHandled) > 60000) { // 60 seconds
                        state.lastSessionExpirationHandled = now
                        log.warn "Session expired! Scheduling re-authentication..."
                        handleSessionExpiration()
                    } else {
                        // Already handled recently, just update status silently
                        if (settings.logEnable) log.debug "Session expired (throttled, already handling)"
                    }
                    return
                }
                
                // Parse all attributes from the response
                if (data.containsKey("roomTemperatureDisplay") && data.roomTemperatureDisplay?.value != null) {
                    def tempC = data.roomTemperatureDisplay.value
                    def tempF = celsiusToFahrenheit(tempC)
                    sendEvent(name: "temperature", value: tempF, unit: "F")
                }
                
                if (data.containsKey("roomSetpoint")) {
                    def setpointC = data.roomSetpoint
                    def setpointF = celsiusToFahrenheit(setpointC)
                    sendEvent(name: "heatingSetpoint", value: setpointF, unit: "F")
                }
                
                if (data.containsKey("setpointMode")) {
                    def apiMode = data.setpointMode
                    def previousMode = device.currentValue("thermostatMode")
                    def thermostatMode = mapApiModeToThermostatMode(apiMode)
                    sendEvent(name: "thermostatMode", value: thermostatMode)
                    updateOperatingState()
                    
                    // Handle auto-off only when mode actually changes
                    def wasOn = (previousMode == "heat" || previousMode == "auto")
                    def isOn = (thermostatMode == "heat" || thermostatMode == "auto")
                    
                    if (!wasOn && isOn) {
                        // Transition from off -> heat/auto: start auto-off timer
                        scheduleAutoOff()
                    } else if (wasOn && !isOn) {
                        // Transition from heat/auto -> off: cancel auto-off timer
                        cancelAutoOff()
                    }
                }
                
                if (data.containsKey("outputPercentDisplay") && data.outputPercentDisplay?.percent != null) {
                    sendEvent(name: "heatingOutput", value: data.outputPercentDisplay.percent)
                }
                
                if (data.containsKey("airFloorMode")) {
                    sendEvent(name: "airFloorMode", value: data.airFloorMode)
                }
                
                sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
                sendEvent(name: "connectionStatus", value: "connected")
                
            } catch (Exception parseError) {
                log.error "Error parsing response: ${parseError.message}"
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if (e.statusCode == 200) {
            // 200 OK but parsing failed - log details
            log.warn "Got 200 OK but response parsing had issues"
        } else if (e.statusCode == 401) {
            // Unauthorized - session expired
            sendEvent(name: "connectionStatus", value: "session_expired")
            // Throttle: only handle expiration once per minute
            def lastHandled = state.lastSessionExpirationHandled ?: 0
            def now = now()
            if ((now - lastHandled) > 60000) { // 60 seconds
                state.lastSessionExpirationHandled = now
                log.warn "HTTP 401 - Session expired! Scheduling re-authentication..."
                handleSessionExpiration()
            } else {
                if (settings.logEnable) log.debug "HTTP 401 - Session expired (throttled, already handling)"
            }
        } else {
            log.error "HTTP ${e.statusCode} getting attributes: ${e.message}"
        }
    } catch (Exception e) {
        log.error "Exception getting attributes: ${e.message}"
    }
}

def getDeviceAttribute(attributeName) {
    def deviceId = state.deviceId ?: settings.deviceId
    // Always prefer settings.sessionId (from Manager) over state
    def sessionId = settings.sessionId ?: state.sessionId
    def baseUrl = settings.apiBaseUrl ?: "https://schluterditraheat.com"
    
    if (!deviceId || !sessionId) {
        log.error "Missing deviceId or sessionId"
        return
    }
    
    if (settings.logEnable) log.debug "Using session: ${sessionId?.substring(0,20)}..."
    
    // Ensure we build the URL correctly
    def baseUrlClean = baseUrl.replaceAll(/\/api\/?$/, '')  // Remove /api if present
    def url = "${baseUrlClean}/api/device/${deviceId}/attribute?attributes=${attributeName}"
    
    if (settings.logEnable) log.debug "Getting attribute ${attributeName} from ${url}"
    
    def params = [
        uri: url,
        headers: [
            "Session-Id": sessionId,
            "Accept": "Application/Json",
            "Content-Type": "Application/Json",
            "Cache-Control": "No-Cache"
        ],
        contentType: "application/json"
    ]
    
    try {
        httpGet(params) { response ->
            try {
                if (settings.logEnable) log.debug "Response status: ${response.status}"
                
                def data = response.data
                if (settings.logEnable) log.debug "Response data for ${attributeName}: ${data}"
                
                if (!data || data == [:]) {
                    if (settings.logEnable) log.debug "Empty response for ${attributeName}"
                    return
                }
                
                // Parse response based on attribute
                if (attributeName == "roomTemperatureDisplay" && data.containsKey("roomTemperatureDisplay")) {
                    if (data.roomTemperatureDisplay?.value != null) {
                        def tempC = data.roomTemperatureDisplay.value
                        def tempF = celsiusToFahrenheit(tempC)
                        sendEvent(name: "temperature", value: tempF, unit: "F")
                    }
                }
                else if (attributeName == "roomSetpoint" && data.containsKey("roomSetpoint")) {
                    def setpointC = data.roomSetpoint
                    def setpointF = celsiusToFahrenheit(setpointC)
                    sendEvent(name: "heatingSetpoint", value: setpointF, unit: "F")
                }
                else if (attributeName == "setpointMode" && data.containsKey("setpointMode")) {
                    def apiMode = data.setpointMode
                    def previousMode = device.currentValue("thermostatMode")
                    def thermostatMode = mapApiModeToThermostatMode(apiMode)
                    sendEvent(name: "thermostatMode", value: thermostatMode)
                    updateOperatingState()
                    
                    // Handle auto-off only when mode actually changes
                    def wasOn = (previousMode == "heat" || previousMode == "auto")
                    def isOn = (thermostatMode == "heat" || thermostatMode == "auto")
                    
                    if (!wasOn && isOn) {
                        scheduleAutoOff()
                    } else if (wasOn && !isOn) {
                        cancelAutoOff()
                    }
                }
                else if (attributeName == "outputPercentDisplay" && data.containsKey("outputPercentDisplay")) {
                    if (data.outputPercentDisplay?.percent != null) {
                        sendEvent(name: "heatingOutput", value: data.outputPercentDisplay.percent)
                    }
                }
                else if (attributeName == "airFloorMode" && data.containsKey("airFloorMode")) {
                    sendEvent(name: "airFloorMode", value: data.airFloorMode)
                }
                
                // Check for session expiration
                if (data.error?.code == "USRSESSEXP") {
                    sendEvent(name: "connectionStatus", value: "session_expired")
                    // Throttle: only handle expiration once per minute
                    def lastHandled = state.lastSessionExpirationHandled ?: 0
                    def now = now()
                    if ((now - lastHandled) > 60000) { // 60 seconds
                        state.lastSessionExpirationHandled = now
                        log.warn "Session expired! Scheduling re-authentication..."
                        handleSessionExpiration()
                    } else {
                        // Already handled recently, just update status silently
                        if (settings.logEnable) log.debug "Session expired (throttled, already handling)"
                    }
                    return
                }
                
                sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
                sendEvent(name: "connectionStatus", value: "connected")
                
            } catch (Exception parseError) {
                log.error "Error parsing ${attributeName} response: ${parseError.message}"
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if (e.statusCode == 200) {
            // 200 OK but parsing failed - log details
            log.warn "Got 200 OK for ${attributeName} but response parsing had issues"
        } else if (e.statusCode == 401) {
            // Unauthorized - session expired
            sendEvent(name: "connectionStatus", value: "session_expired")
            // Throttle: only handle expiration once per minute
            def lastHandled = state.lastSessionExpirationHandled ?: 0
            def now = now()
            if ((now - lastHandled) > 60000) { // 60 seconds
                state.lastSessionExpirationHandled = now
                log.warn "HTTP 401 - Session expired! Scheduling re-authentication..."
                handleSessionExpiration()
            } else {
                if (settings.logEnable) log.debug "HTTP 401 - Session expired (throttled, already handling)"
            }
        } else {
            log.error "HTTP ${e.statusCode} getting ${attributeName}: ${e.message}"
        }
    } catch (Exception e) {
        log.error "Exception getting attribute ${attributeName}: ${e.message}"
    }
}

def setHeatingSetpoint(temperature) {
    // Convert Fahrenheit to Celsius for API
    def tempC = fahrenheitToCelsius(temperature)
    setDeviceAttribute("roomSetpoint", tempC)
}

def setThermostatMode(mode) {
    log.info "Setting thermostat mode to ${mode}"
    
    // Only allow heating modes: "off", "heat", "auto"
    // Reject "cool", "fan", "emergency heat", etc.
    def validModes = ["off", "heat", "auto"]
    if (!validModes.contains(mode)) {
        log.error "Invalid thermostat mode: ${mode}. This device only supports: ${validModes.join(', ')}"
        return
    }
    
    def previousMode = device.currentValue("thermostatMode")
    
    def apiMode = mapThermostatModeToApiMode(mode)
    if (apiMode) {
        setDeviceAttribute("setpointMode", apiMode)
        sendEvent(name: "thermostatMode", value: mode)
        updateOperatingState()
        
        // Handle auto-off functionality
        if (mode == "heat" || mode == "auto") {
            // Turned on - schedule auto-off if enabled
            scheduleAutoOff()
        } else if (mode == "off") {
            // Turned off - cancel any scheduled auto-off
            cancelAutoOff()
        }
    } else {
        log.error "Failed to map thermostat mode: ${mode}"
    }
}

def updateTemperature(temperature) {
    // Update temperature reading (for manual updates if needed)
    sendEvent(name: "temperature", value: temperature, unit: "F")
}


def setDeviceAttribute(attributeName, value) {
    def deviceId = state.deviceId ?: settings.deviceId
    // Always prefer settings.sessionId (from Manager) over state
    def sessionId = settings.sessionId ?: state.sessionId
    def baseUrl = settings.apiBaseUrl ?: "https://schluterditraheat.com"
    
    if (!deviceId || !sessionId) {
        log.error "Missing deviceId or sessionId"
        return
    }
    
    if (settings.logEnable) log.debug "Using session: ${sessionId?.substring(0,20)}..."
    
    // Build URL correctly
    def baseUrlClean = baseUrl.replaceAll(/\/api\/?$/, '')
    def url = "${baseUrlClean}/api/device/${deviceId}/attribute"
    
    if (settings.logEnable) log.debug "Setting ${attributeName} to ${value} on device ${deviceId}"
    
    // Use just the attribute name and value (not wrapped in "attributes")
    def body = [
        (attributeName): value
    ]
    
    def params = [
        uri: url,
        headers: [
            "Session-Id": sessionId,
            "Accept": "Application/Json",
            "Content-Type": "Application/Json"
        ],
        contentType: "application/json",
        body: body
    ]
    
    try {
        httpPutJson(params) { response ->
            try {
                def data = response.data
                
                // Check for session expiration in response
                if (data?.error?.code == "USRSESSEXP") {
                    sendEvent(name: "connectionStatus", value: "session_expired")
                    // Throttle: only handle expiration once per minute
                    def lastHandled = state.lastSessionExpirationHandled ?: 0
                    def now = now()
                    if ((now - lastHandled) > 60000) { // 60 seconds
                        state.lastSessionExpirationHandled = now
                        log.warn "Session expired in response! Scheduling re-authentication..."
                        handleSessionExpiration()
                    } else {
                        if (settings.logEnable) log.debug "Session expired in response (throttled, already handling)"
                    }
                    return
                }
                
                if (response.status == 200 || response.status == 204) {
                    if (settings.logEnable) log.debug "Successfully set ${attributeName} to ${value}"
                    
                    // Update local state immediately
                    switch(attributeName) {
                        case "roomSetpoint":
                            def setpointF = celsiusToFahrenheit(value)
                            sendEvent(name: "heatingSetpoint", value: setpointF, unit: "F")
                            break
                    case "setpointMode":
                        def thermostatMode = mapApiModeToThermostatMode(value)
                        sendEvent(name: "thermostatMode", value: thermostatMode)
                        updateOperatingState()
                        
                        // Handle auto-off when mode is set via API
                        if (thermostatMode == "heat" || thermostatMode == "auto") {
                            scheduleAutoOff()
                        } else if (thermostatMode == "off") {
                            cancelAutoOff()
                        }
                        break
                    }
                    
                    // Refresh to get actual state
                    runIn(2, refresh)
                } else if (response.status == 401) {
                    // Unauthorized - session expired
                    sendEvent(name: "connectionStatus", value: "session_expired")
                    // Throttle: only handle expiration once per minute
                    def lastHandled = state.lastSessionExpirationHandled ?: 0
                    def now = now()
                    if ((now - lastHandled) > 60000) { // 60 seconds
                        state.lastSessionExpirationHandled = now
                        log.warn "HTTP 401 - Session expired! Scheduling re-authentication..."
                        handleSessionExpiration()
                    } else {
                        if (settings.logEnable) log.debug "HTTP 401 - Session expired (throttled, already handling)"
                    }
                } else {
                    log.error "Failed to set ${attributeName}: HTTP ${response.status}"
                }
            } catch (Exception parseError) {
                log.error "Error parsing set attribute response: ${parseError.message}"
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if (e.statusCode == 401) {
            sendEvent(name: "connectionStatus", value: "session_expired")
            // Throttle: only handle expiration once per minute
            def lastHandled = state.lastSessionExpirationHandled ?: 0
            def now = now()
            if ((now - lastHandled) > 60000) { // 60 seconds
                state.lastSessionExpirationHandled = now
                log.warn "HTTP 401 - Session expired! Scheduling re-authentication..."
                handleSessionExpiration()
            } else {
                if (settings.logEnable) log.debug "HTTP 401 - Session expired (throttled, already handling)"
            }
        } else {
            log.error "Exception setting attribute ${attributeName}: ${e.message}"
        }
    } catch (Exception e) {
        log.error "Exception setting attribute ${attributeName}: ${e.message}"
    }
}

def updateOperatingState() {
    def mode = device.currentValue("thermostatMode")
    def output = device.currentValue("heatingOutput")
    
    def operatingState = "idle"
    
    if (mode && mode != "off") {
        if (output != null && output > 0) {
            operatingState = "heating"
        }
    }
    
    sendEvent(name: "thermostatOperatingState", value: operatingState)
}

// Mode mapping functions
def mapApiModeToThermostatMode(apiMode) {
    // API modes: "off", "manual", "auto"
    // Hubitat modes: "off", "heat", "auto"
    switch(apiMode) {
        case "off":
            return "off"
        case "manual":
            return "heat"
        case "auto":
            return "auto"
        default:
            return "off"
    }
}

def mapThermostatModeToApiMode(thermostatMode) {
    // Hubitat modes: "off", "heat", "auto"
    // API modes: "off", "manual", "auto"
    switch(thermostatMode) {
        case "off":
            return "off"
        case "heat":
            return "manual"
        case "auto":
            return "auto"
        default:
            return null
    }
}

def scheduleAutoOff() {
    def autoOffHours = settings.autoOffHours ?: 0
    if (autoOffHours > 0) {
        def seconds = autoOffHours * 3600
        unschedule(autoOffHandler)
        runIn(seconds, autoOffHandler)
        if (settings.logEnable) log.debug "Auto-off scheduled for ${autoOffHours} hours"
    } else {
        if (settings.logEnable) log.debug "Auto-off disabled (set to 0 hours)"
    }
}

def cancelAutoOff() {
    unschedule(autoOffHandler)
    if (settings.logEnable) log.debug "Auto-off cancelled"
}

def autoOffHandler() {
    def currentMode = device.currentValue("thermostatMode")
    if (currentMode == "heat" || currentMode == "auto") {
        log.info "Auto-off triggered after ${settings.autoOffHours} hours. Turning thermostat off."
        setThermostatMode("off")
    }
}

def handleSessionExpiration() {
    // Check if we already scheduled a re-authentication request
    // This prevents multiple devices from all scheduling requests
    def now = now()
    def lastReAuthAttempt = state.lastReAuthAttempt ?: 0
    def minInterval = 30000 // Minimum 30 seconds between re-auth attempts
    
    if (state.reAuthScheduled) {
        if (settings.logEnable) log.debug "Re-authentication already scheduled, skipping..."
        return
    }
    
    // Throttle: Don't attempt re-auth more than once every 2 minutes
    minInterval = 120000 // 2 minutes
    if (lastReAuthAttempt > 0 && (now - lastReAuthAttempt) < minInterval) {
        def remainingSeconds = Math.round((minInterval - (now - lastReAuthAttempt)) / 1000)
        if (settings.logEnable) log.debug "Re-authentication throttled. Wait ${remainingSeconds} more seconds..."
        return
    }
    
    // Add random delay to prevent all devices from hitting the API at once
    // Use very wide spread: 60-120 seconds to better distribute requests and avoid rate limits
    def randomDelay = Math.random() * 60 + 60 // 60-120 seconds random delay
    def delaySeconds = Math.round(randomDelay)
    
    state.reAuthScheduled = true
    state.lastReAuthAttempt = now
    log.info "Session expired. Will request re-authentication in ${delaySeconds} seconds to avoid rate limiting..."
    
    // Schedule re-authentication with random delay
    runIn(delaySeconds, requestReAuthentication)
}

def requestReAuthentication() {
    state.reAuthScheduled = false // Clear the scheduled flag
    
    // Try to get parent app and trigger re-authentication
    def parentApp = getParent()
    if (parentApp) {
        log.info "Requesting parent app to re-authenticate..."
        def success = parentApp.reAuthenticate()
        
        if (success) {
            // Wait a moment for session to propagate, then refresh
            runIn(3, refresh)
        } else {
            // If re-authentication failed (e.g., rate limited), retry later
            log.warn "Re-authentication not successful. Will retry later..."
            runIn(30, refresh)
        }
    } else {
        log.error "Cannot re-authenticate: parent app not found. Please log in manually in the Manager app."
    }
}

// Temperature conversion functions
def celsiusToFahrenheit(celsius) {
    return (celsius * 9/5) + 32
}

def fahrenheitToCelsius(fahrenheit) {
    return (fahrenheit - 32) * 5/9
}

// Note: Authentication is handled by the Manager app
// This driver receives the session ID from the manager

