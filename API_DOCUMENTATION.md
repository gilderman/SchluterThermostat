# Schluter Ditra-Heat API Documentation

## Overview
This documentation covers the Schluter Ditra-Heat cloud API. The thermostats are OEM Sinope devices and use the Neviweb cloud platform.

## Base URLs
- **Main API**: `https://schluterditraheat.com/api/`
- **Mobile API**: `https://mobile-api.neviweb.com/api/`
- **Auth API**: `https://auth.neviweb.com/api/`

## Authentication

### Session Management
All API requests (except login) require a `Session-Id` header:
```
Session-Id: <session_id>
```

### User Login (Username/Password)
**Endpoint**: `POST https://schluterditraheat.com/api/login`

**Request Body**:
```json
{
  "username": "user@example.com",
  "password": "your_password",
  "interface": "schluter"
}
```

**Response**:
```json
{
  "user": {"id": 74011, "email": "..."},
  "account": {"id": 70178},
  "session": "9b96613eb01a4e6bb43a3f2d1d3393d2ddebbab93e32f547"
}
```

**Alternative Login Endpoint**: `POST https://mobile-api.neviweb.com/api/login`

**Request Body**:
```json
{
  "username": "user@example.com",
  "password": "your_password",
  "interface": "schluter",
  "stayConnected": true
}
```

**Response**:
```json
{
  "session": "<session_id>",
  "refreshToken": "<refresh_token>",
  "user": {...},
  "error": null
}
```

### Connect with Refresh Token
**Endpoint**: `POST https://schluterditraheat.com/api/connect`

**Headers**:
- `refreshToken`: Refresh token from login
- `Content-Type`: `application/json`

**Response**:
```json
{
  "session": "<session_id>",
  "error": null
}
```

### HTTP Basic Auth for Web Interface
The Schluter web interface requires HTTP Basic Authentication:
- **Username**: `schluter`
- **Password**: `CyEK7zOkM5Ivb1EKrxhK`

This is used for accessing the WebView interface, not for user authentication.

## Device Management

### Get Locations
**Endpoint**: `GET https://schluterditraheat.com/api/locations`

**Headers**:
- `Session-Id`: `<session_id>`

**Response**:
```json
[{
  "id": 84791,
  "name": "Bellevue Residence",
  "account$id": 70178
}]
```

### Get Devices
**Endpoint**: `GET https://schluterditraheat.com/api/devices`

**Query Parameters**:
- `includedLocationChildren`: `true`
- `location$id`: `<location_id>` (integer)

**Headers**:
- `Session-Id`: `<session_id>`
- `Accept`: `Application/Json`
- `Content-Type`: `Application/Json`
- `Cache-Control`: `No-Cache`

**Response**:
```json
[{
  "id": 464306,
  "name": "Guest",
  "family": "740",
  "identifier": "8c4b14fffe740258",
  "location$id": 84791
}]
```

### Get Device Attribute
**Endpoint**: `GET https://schluterditraheat.com/api/device/{deviceId}/attribute`

**Query Parameters**:
- `attributes`: `<attribute_name>` (comma-separated for multiple, e.g., "roomTemperatureDisplay,roomSetpoint,outputPercentDisplay")

**Headers**:
- `Session-Id`: `<session_id>`

**Response**:
```json
{
  "roomTemperatureDisplay": {"status": "on", "value": 16.11},
  "roomSetpoint": 29.44,
  "outputPercentDisplay": {"percent": 0, "sourceType": "heating"}
}
```

### Set Device Attribute
**Endpoint**: `PUT https://schluterditraheat.com/api/device/{deviceId}/attribute`

**Headers**:
- `Session-Id`: `<session_id>`
- `Content-Type`: `application/json`

**Request Body**:
```json
{
  "roomSetpoint": 22.0
}
```

**Response**:
```json
{
  "roomSetpoint": 22.0
}
```

**Note**: Use PUT method, not POST. The request body uses direct attribute names, not wrapped in "attributes".

## Attribute Reference

| Attribute | Type | Description | Example |
|-----------|------|-------------|---------|
| `roomTemperatureDisplay` | object | Current room temperature | `{"status":"on","value":16.11}` |
| `roomSetpoint` | number | Target temperature (°C) | `29.44` |
| `outputPercentDisplay` | object | Heating output percentage | `{"percent":100,"sourceType":"heating"}` |
| `setpointMode` | string | On/off/auto mode | `"off"`, `"manual"`, `"auto"` |
| `airFloorMode` | string | Sensor type | `"floor"`, `"regulator"` |
| `occupancyMode` | string | Home/away mode | `"home"`, `"away"` |

## Control Operations

### Turn On
```json
PUT /api/device/{id}/attribute
{"setpointMode": "manual"}
```

### Turn Off
```json
PUT /api/device/{id}/attribute
{"setpointMode": "off"}
```

### Set Temperature
```json
PUT /api/device/{id}/attribute
{"roomSetpoint": 22.0}
```

### Set to Auto Mode
```json
PUT /api/device/{id}/attribute
{"setpointMode": "auto"}
```

## Device Association (WiFi Setup)

### Get Association Token
**Endpoint**: `POST https://schluterditraheat.com/api/device/associate`

**Headers**:
- `Session-Id`: `<session_id>`

**Request Body**:
```json
{
  "location": <location_id>
}
```

**Response**:
```json
{
  "token": "<association_token>"
}
```

### Check Device Association
**Endpoint**: `GET https://schluterditraheat.com/api/device/associate/{tokenId}/check`

**Headers**:
- `Session-Id`: `<session_id>`

## Geofencing API

### Get Geofences
**Endpoint**: `GET https://schluterditraheat.com/api/geofences`

**Query Parameters**:
- `location$id`: `<location_id>`

**Headers**:
- `Session-Id`: `<session_id>`

### Create Geofence
**Endpoint**: `POST https://schluterditraheat.com/api/geofence`

**Headers**:
- `Session-Id`: `<session_id>`

### Update Geofence
**Endpoint**: `PUT https://schluterditraheat.com/api/geofence/{id}`

**Headers**:
- `Session-Id`: `<session_id>`

### Delete Geofence
**Endpoint**: `DELETE https://schluterditraheat.com/api/geofence/{id}`

**Headers**:
- `Session-Id`: `<session_id>`

## Mobile API (Geofence Transitions)

### Login with Refresh Token
**Endpoint**: `POST https://mobile-api.neviweb.com/api/login`

**Request Body**:
```json
{
  "refreshToken": "<refresh_token>"
}
```

**Response**:
```json
{
  "accessToken": "<access_token>",
  "refreshToken": "<refresh_token>"
}
```

### Geofence Transition In
**Endpoint**: `GET https://mobile-api.neviweb.com/api/geofence/{id}/in`

**Headers**:
- `Authorization`: `<access_token>`

### Geofence Transition Out
**Endpoint**: `GET https://mobile-api.neviweb.com/api/geofence/{id}/out`

**Headers**:
- `Authorization`: `<access_token>`

## Error Handling

All responses may include an `error` object:
```json
{
  "error": {
    "code": "<error_code>",
    "message": "<error_message>"
  }
}
```

**Common Error Codes**:
- `USRSESSEXP` - Session expired

**Common HTTP Status Codes**:
- `200` - Success
- `204` - Success (no content)
- `401` - Unauthorized (session expired)
- `404` - Not found
- `500` - Server error

## Code Examples

### cURL Examples

#### Login with Username/Password
```bash
curl -X POST https://schluterditraheat.com/api/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "user@example.com",
    "password": "your_password",
    "interface": "schluter"
  }'
```

#### Authenticate with Refresh Token
```bash
curl -X POST https://schluterditraheat.com/api/connect \
  -H "refreshToken: YOUR_REFRESH_TOKEN" \
  -H "Content-Type: application/json"
```

#### Get Devices
```bash
curl -X GET "https://schluterditraheat.com/api/devices?includedLocationChildren=true&location\$id=YOUR_LOCATION_ID" \
  -H "Session-Id: YOUR_SESSION_ID" \
  -H "Accept: Application/Json" \
  -H "Content-Type: Application/Json"
```

#### Get Device Attributes
```bash
curl -X GET "https://schluterditraheat.com/api/device/YOUR_DEVICE_ID/attribute?attributes=roomTemperatureDisplay,roomSetpoint,outputPercentDisplay" \
  -H "Session-Id: YOUR_SESSION_ID"
```

#### Set Device Attribute
```bash
curl -X PUT https://schluterditraheat.com/api/device/YOUR_DEVICE_ID/attribute \
  -H "Session-Id: YOUR_SESSION_ID" \
  -H "Content-Type: application/json" \
  -d '{"roomSetpoint": 22.0}'
```

### JavaScript/Fetch Examples

#### Login
```javascript
const loginResp = await fetch('https://schluterditraheat.com/api/login', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({
    username: 'user@email.com',
    password: 'password',
    interface: 'schluter'
  })
});
const {session} = await loginResp.json();
```

#### Get Devices
```javascript
const devicesResp = await fetch(
  'https://schluterditraheat.com/api/devices?includedLocationChildren=true&location$id=YOUR_LOCATION_ID',
  {
    headers: {
      'Session-Id': 'YOUR_SESSION_ID',
      'Accept': 'Application/Json',
      'Content-Type': 'Application/Json'
    }
  }
);
const devices = await devicesResp.json();
```

#### Get Device Attributes
```javascript
const tempResp = await fetch(
  'https://schluterditraheat.com/api/device/464306/attribute?attributes=roomTemperatureDisplay,roomSetpoint',
  {headers: {'Session-Id': session}}
);
const data = await tempResp.json();
console.log('Temperature:', data.roomTemperatureDisplay.value);
```

#### Set Device Attribute
```javascript
// Turn on
await fetch('https://schluterditraheat.com/api/device/464306/attribute', {
  method: 'PUT',
  headers: {
    'Session-Id': session,
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({setpointMode: 'manual'})
});

// Set temperature
await fetch('https://schluterditraheat.com/api/device/464306/attribute', {
  method: 'PUT',
  headers: {
    'Session-Id': session,
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({roomSetpoint: 22.0})
});
```

### Python Example
```python
import requests

# Login with username/password
response = requests.post(
    "https://schluterditraheat.com/api/login",
    json={
        "username": "user@example.com",
        "password": "your_password",
        "interface": "schluter"
    }
)
session_id = response.json()["session"]

# Get devices
response = requests.get(
    "https://schluterditraheat.com/api/devices",
    params={"includedLocationChildren": "true", "location$id": "YOUR_LOCATION_ID"},
    headers={"Session-Id": session_id}
)
devices = response.json()

# Get temperature
device_id = devices[0]["id"]
response = requests.get(
    f"https://schluterditraheat.com/api/device/{device_id}/attribute",
    params={"attributes": "roomTemperatureDisplay,roomSetpoint"},
    headers={"Session-Id": session_id}
)
data = response.json()
print(f"Temperature: {data['roomTemperatureDisplay']['value']}°C")

# Set temperature
response = requests.put(
    f"https://schluterditraheat.com/api/device/{device_id}/attribute",
    headers={"Session-Id": session_id, "Content-Type": "application/json"},
    json={"roomSetpoint": 22.0}
)
```

## Important Notes

1. **Use PUT, not POST** for setting attributes
2. **Temperature is in Celsius** (not Fahrenheit)
3. **`interface: "schluter"`** is required in login
4. **Session IDs expire** - you'll need to re-authenticate periodically
5. **The `location$id` parameter uses `$` not `&`** - this is intentional
6. **Request body format**: When setting attributes, use direct attribute names (e.g., `{"roomSetpoint": 22.0}`), not wrapped in "attributes"
7. **setpointMode values**: `"off"`, `"manual"`, `"auto"`
8. **Session Management**: The session ID is obtained from the login/connect endpoint and must be included in all subsequent requests
9. **Error code `USRSESSEXP`** = session expired, re-login to get new session

