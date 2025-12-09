# Push Notifications / Webhook Support

This document describes how to add push notification support to the Schluter Thermostat Manager app for real-time updates.

## Overview

The push notification feature allows you to receive real-time updates from Schluter thermostats without polling. This requires setting up a webhook endpoint in the Manager app and configuring an external service (like Firebase Cloud Functions) to forward notifications.

## Implementation

### 1. Add Mappings to Manager App

Add this to the `mappings` section in `schluter-thermostat-manager.groovy`:

```groovy
mappings {
    path("/notifications") {
        action: [
            POST: "notificationsCallback"
        ]
    }
}
```

### 2. Add Push Notifications Section to UI

Add this section to the `mainPage()` function:

```groovy
section("Push Notifications (Optional)") {
    paragraph "For real-time updates, you can register this webhook URL with a Firebase Cloud Function or notification bridge."
    if (state.accessToken || createAccessToken()) {
        def webhookUrl = getWebhookUrl()
        paragraph "<small>${webhookUrl}</small>"
        input name: "enableNotifications", type: "bool", title: "Enable push notifications", defaultValue: false
    }
}
```

### 3. Add Notification Callback Handler

Add this function to handle incoming notifications:

```groovy
def notificationsCallback() {
    def json = request.JSON
    
    if (settings.logEnable) {
        log.debug "Received notification callback"
        log.debug "Headers: ${request.headers}"
        log.debug "Body: ${json}"
    }
    
    try {
        // Parse Firebase notification or Schluter webhook
        def deviceId = json?.deviceId ?: json?.device?.id
        def data = json?.data ?: json
        
        if (!deviceId) {
            log.warn "Notification received but no device ID found"
            return [status: "error", message: "No device ID"]
        }
        
        log.info "Notification for device ${deviceId}"
        
        // Find the child device
        def child = getChildDevices().find { it.deviceNetworkId == "schluter-thermostat-${deviceId}" }
        
        if (!child) {
            log.warn "Device ${deviceId} not found in child devices"
            return [status: "error", message: "Device not found"]
        }
        
        // Update device attributes from notification
        if (data.roomTemperatureDisplay?.value != null) {
            def tempC = data.roomTemperatureDisplay.value
            def tempF = celsiusToFahrenheit(tempC)
            child.sendEvent(name: "temperature", value: tempF, unit: "F")
        }
        if (data.roomSetpoint != null) {
            def setpointC = data.roomSetpoint
            def setpointF = celsiusToFahrenheit(setpointC)
            child.sendEvent(name: "heatingSetpoint", value: setpointF, unit: "F")
        }
        if (data.setpointMode != null) {
            def switchVal = data.setpointMode == "off" ? "off" : "on"
            child.sendEvent(name: "switch", value: switchVal)
            child.sendEvent(name: "setpointMode", value: data.setpointMode)
        }
        if (data.outputPercentDisplay?.percent != null) {
            child.sendEvent(name: "heatingOutput", value: data.outputPercentDisplay.percent)
        }
        if (data.airFloorMode != null) {
            child.sendEvent(name: "airFloorMode", value: data.airFloorMode)
        }
        
        child.sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        child.sendEvent(name: "connectionStatus", value: "connected")
        
        log.info "✓ Updated device ${deviceId} from notification"
        
        return [status: "success"]
        
    } catch (Exception e) {
        log.error "Error processing notification: ${e.message}"
        return [status: "error", message: e.message]
    }
}
```

### 4. Add Helper Functions

Add these helper functions:

```groovy
def getWebhookUrl() {
    // Get the full webhook URL for this app
    def accessToken = createAccessToken()
    def webhookUrl = "${getFullApiServerUrl()}/notifications?access_token=${accessToken}"
    return webhookUrl
}

def showWebhookInfo() {
    def webhookUrl = getWebhookUrl()
    return webhookUrl
}
```

## Webhook URL Format

The webhook URL will be in the format:
```
https://your-hubitat-hub.local/apps/api/[app-id]/notifications?access_token=[token]
```

## Notification Payload Format

The webhook expects a JSON payload in one of these formats:

### Format 1: Direct device data
```json
{
  "deviceId": 464306,
  "roomTemperatureDisplay": {
    "status": "on",
    "value": 22.5
  },
  "roomSetpoint": 23.0,
  "setpointMode": "manual",
  "outputPercentDisplay": {
    "percent": 50,
    "sourceType": "heating"
  },
  "airFloorMode": "regulator"
}
```

### Format 2: Nested device structure
```json
{
  "device": {
    "id": 464306
  },
  "data": {
    "roomTemperatureDisplay": {
      "status": "on",
      "value": 22.5
    },
    "roomSetpoint": 23.0
  }
}
```

## Temperature Conversion

Note that the notification handler converts temperatures from Celsius (API format) to Fahrenheit (display format) using the `celsiusToFahrenheit()` function.

## Setting Up External Notification Bridge

To receive notifications from Schluter, you'll need to set up an external service that:

1. Monitors Schluter API for changes (or receives webhooks from Schluter if available)
2. Forwards notifications to your Hubitat webhook URL

This could be implemented using:
- Firebase Cloud Functions
- AWS Lambda
- A custom Node.js service
- Other cloud functions

## Notes

- The webhook endpoint requires authentication via the `access_token` query parameter
- Notifications are optional - the driver will continue to work with polling if notifications are not configured
- Enable debug logging to troubleshoot notification issues
- The notification handler automatically updates all device attributes when a notification is received

