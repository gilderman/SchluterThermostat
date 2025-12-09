# Schluter Ditra-Heat Hubitat Integration

This integration allows you to control Schluter Ditra-Heat thermostats (OEM Sinope devices) from Hubitat using the Schluter cloud API.

## Files

- `schluter-thermostat-driver.groovy` - Hubitat device driver for individual thermostats
- `schluter-thermostat-manager.groovy` - Hubitat app for managing authentication and device discovery

## Installation

### Step 1: Install the Driver

1. In Hubitat, go to **Drivers Code**
2. Click **+ New Driver**
3. Copy and paste the contents of `schluter-thermostat-driver.groovy`
4. Click **Save**

### Step 2: Install the Manager App

1. In Hubitat, go to **Apps**
2. Click **+ Add User App**
3. Copy and paste the contents of `schluter-thermostat-manager.groovy`
4. Click **Save**

### Step 3: Configure Authentication

1. Open the **Schluter Thermostat Manager** app
2. Go to **Authentication Settings**
3. Enter your Schluter account **Username/Email** and **Password**
4. Click **Login with Username/Password**
5. The app will automatically:
   - Authenticate with the Schluter API
   - Store your session ID
   - Discover your location ID
   - Update all child devices with the session

**Note**: Your credentials are stored securely for automatic re-authentication when sessions expire.

### Step 4: Discover Devices

1. In the Manager app, go to **Discover Devices**
2. Click **Discover Devices**
3. The app will automatically:
   - Use your location ID (auto-discovered during login)
   - Find all thermostats in your location
   - Create child devices for each discovered thermostat
   - Configure each device with the correct settings

## Manual Device Setup (Alternative)

If automatic discovery doesn't work, you can manually add devices:

1. **First, authenticate in the Manager app** (see Step 3 above)
2. Go to **Devices** in Hubitat
3. Click **+ Add Device**
4. Select **Schluter Ditra-Heat Thermostat**
5. Configure:
   - **Device ID**: Your thermostat's device ID (numeric, e.g., 464306)
   - **Session ID**: Automatically set by Manager app
   - **API Base URL**: `https://schluterditraheat.com` (default)
   - **Poll Interval**: How often to refresh in minutes (default: 1)

**Note**: The driver receives its session ID from the Manager app. All authentication happens in the Manager app.

## Features

- **Username/Password Authentication**: Simple login with your Schluter account credentials
- **Automatic Session Management**: Sessions are automatically refreshed when they expire
- **Automatic Location Discovery**: Your location is discovered automatically after login
- **Automatic Device Discovery**: All thermostats are discovered and configured automatically
- **Temperature Reading**: Current room temperature in Fahrenheit
- **Setpoint Control**: Set heating setpoint temperature
- **Mode Control**: Change thermostat mode (off, heat, auto)
- **Heating Output**: View current heating output percentage
- **Auto Polling**: Automatically refresh device state at configurable intervals
- **Manual Refresh**: Refresh button for immediate update
- **Auto-Off Timer**: Optional automatic turn-off after specified hours
- **Connection Status**: Monitor connection and session status

## Troubleshooting

### "No session ID configured"
- Make sure you've logged in through the Manager app
- Check that the session ID is being passed to child devices
- Try logging out and logging back in

### "HTTP Error 401" or Session Expired
- Your session has expired
- The app will automatically attempt to re-authenticate
- If automatic re-authentication fails, manually log in again in the Manager app
- Check that your credentials are still valid

### "Device not found"
- Verify the Device ID is correct
- Make sure the device is associated with your account
- Try running device discovery again

### Attributes not updating
- Check that polling is enabled and the interval is set correctly
- Enable debug logging to see API responses
- Verify your session is still valid

### Rate Limiting
- If you see rate limit errors, the app will automatically retry after a cooldown period
- Reduce polling frequency if you have many devices
- The app includes throttling to prevent excessive API calls

## Configuration Options

### Driver Settings
- **Poll Interval**: How often to poll the device (in minutes, default: 1)
- **Auto-Off Time**: Automatically turn off after this many hours (0 to disable, default: 0)
- **Debug Logging**: Enable detailed logging for troubleshooting

### Manager App Settings
- **API Base URL**: Schluter API endpoint (default: `https://schluterditraheat.com/api`)
- **Debug Logging**: Enable detailed logging for troubleshooting

## How It Works

1. **Authentication**: The Manager app authenticates with the Schluter API using your username and password, obtaining a session ID.

2. **Location Discovery**: After authentication, the app automatically retrieves your location information.

3. **Device Discovery**: The app queries the API for all devices in your location and creates Hubitat child devices for each thermostat.

4. **Session Management**: The Manager app maintains the session and automatically re-authenticates when sessions expire. All child devices receive the session ID automatically.

5. **Device Control**: Each thermostat driver polls the API for current state and can send commands to control the thermostat.

## Technical Details

- **API Base URL**: `https://schluterditraheat.com/api`
- **Authentication**: Username/password login with session-based authentication
- **Temperature Units**: API uses Celsius, driver converts to Fahrenheit for display
- **Session Expiration**: Sessions expire periodically and are automatically refreshed
- **Device Naming**: Discovered devices are prefixed with `[THERMOSTAT]` for easy identification

## References

- Uses Schluter cloud API (schluterditraheat.com)
- Devices are OEM Sinope thermostats
- Uses Neviweb cloud platform

## License

This code is provided as-is for educational and integration purposes. Use at your own risk.
