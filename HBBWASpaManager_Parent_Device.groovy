/*
 *  Hubitat BWA Spa Manager
 *  -> Parent Device Driver
 *
 *  Copyright 2020 Richard Powell
 *   based on work Copyright 2020 Nathan Spencer that he did for SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  CHANGE HISTORY
 *  VERSION     DATE            NOTES
 *  0.9.0       2020-01-30      Initial release with basic access and control of spas
 *  1.0.0       2020-01-31      Updated UI and icons as well as switch functionality that can be controlled with
 *                              Alexa. Added preference for a "Default Temperature When Turned On"
 *  1.1.0       2020-06-03      Additional functionality for aux, temperature range, and heat modes
 *  1.1.1       2020-07-26      Adjusted icons to better match functionality for aux, temperature range and heat modes
 *                              and removed duplicate tile declaration
 *  1.1.2b      2020-09-17      Modified / validated to work on Hubitat
 *  1.1.3
 *
 */

import groovy.transform.Field
import groovy.time.TimeCategory

@Field static int LOG_LEVEL = 3

@Field static String NAMESPACE = "richardpowellus"

@Field static String DEVICE_NAME_PREFIX = "HB BWA SPA"
@Field static String PARENT_DEVICE_NAME = "HB BPA SPA Parent"
@Field static String THERMOSTAT_CHILD_DEVICE_NAME = "HB BWA SPA Thermostat"
@Field static String SWITCH_CHILD_DEVICE_NAME = "HB BWA SPA Switch"

metadata {
    definition (name: PARENT_DEVICE_NAME, namespace: NAMESPACE, author: "Richard Powell") {
        capability "Configuration"
                        
        /* This is a list of attributes sent to us right after we successfully login
         * to Balboa and pull details about Spas linked to the user's account.
         *
         * Hubitat requires attributes to be defined in order for sendEvent(...) to
         * be able to update that attribute.
         */
        attribute "create_user_id", "string"
        attribute "deviceId", "string" // renamed from "device_id"
        attribute "update_user_id", "string"
        attribute "updated_at", "string"
        attribute "__v", "string"
        attribute "active", "string"
        attribute "created_at", "string"
        attribute "_id", "string"
        
        // Additional attributes
        attribute "spaStatus", "string"
    }   
}

@Field static Map PUMP_BUTTON_MAP = [
    1: 4, // Pump 1 maps to Balboa API Button #4
    2: 5, // Pump 2 maps to Balboa API Button #5 etc.
    3: 6,
    4: 7,
    5: 8,
    6: 9]

@Field static Map LIGHT_BUTTON_MAP = [
    1: 17, // Light 1 maps to Balboa API Button #17 etc.
    2: 18]

@Field static Map AUX_BUTTON_MAP = [
    1: 22, // Aux 1 maps to Balboa API Button #22 etc.
    2: 23
    ]

@Field static Map BUTTON_MAP = [
    Blower: 12,
    Mister: 14,
    Aux1: 22,
    Aux2: 23,
    TempRange: 80,
    HeatMode: 81]

def logMessage(level, message) {
    if (level >= LOG_LEVEL) {
        if (level < 3) {
            log.debug message
        } else {
            log.info message
        }
    }
}

def installed() {
}

def updated() {
}

def on() {
    // TODO: Maybe implement some sort of "Turn everything on" feature.
}

def off() {
    // TODO: Implement a "Turn everything off" feature.
}

def sendCommand(action, data) {
	parent.sendCommand(device.currentValue("deviceId"), action, data)
    runIn(2, refresh)
}

def parseDeviceData(Map results) {
    results.each {name, value ->
        sendEvent(name: name, value: value, displayed: true)
    }
}

def createChildDevices(spaConfiguration) {
    // Thermostat
    fetchChild(true, "Thermostat", "Thermostat")
    
    // Pumps
    spaConfiguration.each { k, v ->
        if (k.startsWith("Pump") && v == true) {
            def pumpNumber = k[4].toInteger()
            fetchChild(true, "Switch", "Pump ${pumpNumber}", PUMP_BUTTON_MAP[pumpNumber])
        }
    }
    
    // Lights
    spaConfiguration.each { k, v ->
        if (k.startsWith("Light") && v == true) {
            def lightNumber = k[5].toInteger()
            fetchChild(true, "Switch", "Light ${lightNumber}", LIGHT_BUTTON_MAP[lightNumber])
        }
    }
}

def parsePanelData(encodedData) {
    byte[] decoded = encodedData.decodeBase64()
    
    def messageLength = new BigInteger(1, decoded[0])
    def actualTemperature = new BigInteger(1, decoded[6])
    def currentTimeHour = new BigInteger(1, decoded[7])
    def currentTimeMinute = new BigInteger(1, decoded[8])
    def heatMode
    switch (new BigInteger(1, decoded[9])) {
    	case 0:
        	heatMode = "ready"
            break
        case 1:
        	heatMode = "rest"
            break
        case 2:
        	heatMode = "ready in rest"
            break
        default:
        	heatMode = "none"
            break
    }
    def flag1 = new BigInteger(1, decoded[13])
    def is24HourTime = (flag1 & 2) != 0
    def filterMode
    switch (flag1 & 12) {
        case 4:
        	filterMode = "1"
            break
        case 8:
        	filterMode = "2"
            break
        case 12:
        	filterMode = "1 & 2"
            break
    	case 0:
        default:
        	filterMode = "off"
            break
    }
    def accessibilityType
    switch (flag1 & 48) {
    	case 16:
        	accessibilityType = "Pump Light"
            break
        case 32:
        case 48:
        	accessibilityType = "None"
            break
        default:
        	accessibilityType = "All"
            break
    }
    def temperatureScale = (flag1 & 1) == 0 ? "F" : "C"
    def flag2 = new BigInteger(1, decoded[14])
    def temperatureRange = (flag2 & 4) == 4 ? "high" : "low"
    def isHeating = (flag2 & 48) != 0
    def flag3 = new BigInteger(1, decoded[15])
    
    // Pumps
    def pumpState = []
    pumpState[0] = null
    switch (flag3 & 3) {
        case 1:
        	pumpState[1] = "low"
            break
        case 2:
        	pumpState[1] = "high"
            break
        default:
        	pumpState[1] = "off"
            break
    }
    def pump2State
    switch (flag3 & 12) {
        case 4:
        	pumpState[2] = "low"
            break
        case 8:
        	pumpState[2] = "high"
            break
        default:
        	pumpState[2] = "off"
            break
    }
    def pump3State
    switch (flag3 & 48) {
        case 16:
        	pumpState[3] = "low"
            break
        case 32:
        	pumpState[3] = "high"
            break
        default:
        	pumpState[3] = "off"
            break
    }
    def pump4State
    switch (flag3 & 192) {
        case 64:
        	pumpState[4] = "low"
            break
        case 128:
        	pumpState[4] = "high"
            break
        default:
        	pumpState[4] = "off"
            break
    }
    def flag4 = new BigInteger(1, decoded[16])
    def pump5State
    switch (flag4 & 3) {
        case 1:
        	pumpState[5] = "low"
            break
        case 2:
        	pumpState[5] = "high"
            break
        default:
        	pumpState[5] = "off"
            break
    }
    def pump6State
    switch (flag4 & 12) {
        case 4:
        	pumpState[6] = "low"
            break
        case 8:
        	pumpState[6] = "high"
            break
        default:
        	pumpState[6] = "off"
            break
    }
    
    def byte17 = new BigInteger(1, decoded[17])
    def blowerState
    switch (byte17 & 12) {
        case 4:
        	blowerState = "low"
            break
        case 8:
        	blowerState = "medium"
            break
        case 12:
        	blowerState = "high"
            break
        default:
        	blowerState = "off"
            break
    }
    def flag6 = new BigInteger(1, decoded[18])
    
    // Lights
    def lightState = []
    lightState[0] = null
    lightState[1] = (flag6 & 3) != 0
    lightState[2] = (flag6 & 12) != 0
    
    def byte19 = new BigInteger(1, decoded[19])
    def misterOn = (byte19 & 1) != 0
    def aux1On = (byte19 & 8) != 0
    def aux2On = (byte19 & 16) != 0
    def targetTemperature = new BigInteger(1, decoded[24])
    def byte26 = new BigInteger(1, decoded[26])
    def wifiState
    switch (byte26 & 240) {
    	case 0:
        	wifiState = "OK"
            break
        case 16:
        	wifiState = "Spa Not Communicating"
            break
        case 32:
        	wifiState = "Startup"
            break
        case 48:
        	wifiState = "Prime"
            break
        case 64:
        	wifiState = "Hold"
            break
        case 80:
        	wifiState = "Panel"
            break
    }
    def pumpStateStatus
    if (flag3 < 1 && flag4 < 1 && (byte17 & 3) < 1) {
    	pumpStateStatus = "Off"
    } else {
    	pumpStateStatus = isHeating ? "Low Heat" : "Low"
    }
    
    if (actualTemperature == 255) {
    	actualTemperature = device.currentValue("temperature") * (temperatureScale == "C" ? 2.0F : 1)
    }
    
    if (temperatureScale == "C") {
    	actualTemperature /= 2.0F
    	targetTemperature /= 2.0F
    }
    
	logMessage(2, "Message Length: ${messageLength}\n"
                + "Actual Temperature: ${actualTemperature}\n"
                + "Current Time Hour: ${currentTimeHour}\n"
                + "Current Time Minute: ${currentTimeMinute}\n"
                + "Is 24-Hour Time: ${is24HourTime}\n"
                + "Temperature Scale: ${temperatureScale}\n"
                + "Target Temperature: ${targetTemperature}\n"
                + "Filter Mode: ${filterMode}\n"
                + "Accessibility Type: ${accessibilityType}\n"
                + "Temperature Range: ${temperatureRange}\n"
                + "Light-1 On: ${light1On}\n"
                + "Light-2 On: ${light2On}\n"
                + "Heat Mode: ${heatMode}\n"
                + "Is Heating: ${isHeating}\n"
                + "pump1State: ${pumpState[1]}\n"
                + "pump2State: ${pumpState[2]}\n"
                + "pump3State: ${pumpState[3]}\n"
                + "pump4State: ${pumpState[4]}\n"
                + "pump5State: ${pumpState[5]}\n"
                + "pump6State: ${pumpState[6]}\n"
                + "blowerState: ${blowerState}\n"
                + "misterOn: ${misterOn}\n"
                + "aux1On: ${aux1On}\n"
                + "aux2On: ${aux2On}\n"
                + "pumpStateStatus: ${pumpStateStatus}\n"
                + "wifiState: ${wifiState}\n"
    )
    
    // Send Thermostat Events
    def thermostat = fetchChild(false, "Thermostat", "Thermostat")
    thermostat.sendEvents([
        [name: "thermostatMode", value: isHeating ? "heat" : "off"],
        [name: "thermostatOperatingState", value: isHeating ? "heating" : "idle"],
    ])
    thermostat.sendEventsWithUnits([
        [name: "temperature", value: actualTemperature, unit: temperatureScale],
        [name: "heatingSetpoint", value: targetTemperature, unit: temperatureScale]
    ])
    
    // Send Pump Events
    for (int i = 1; i <= 6; ++i) {
        def tempPump = fetchChild(false, "Switch", "Pump ${i}")
        if (tempPump != null) {
            tempPump.parse(pumpState[i])
        }
    }
    
    // Send Light Events
    for (int i = 1; i <= 2; ++i) {
        def tempLight = fetchChild(false, "Switch", "Light ${i}")
        if (tempLight != null) {
            tempLight.parse(lightState[i])
        }
    }
    
    //sendEvent(name: "blower", value: blowerState)
    //sendEvent(name: "mister", value: misterOn ? "on" : "off")
    //sendEvent(name: "aux1", value: aux1On ? "on" : "off")
    //sendEvent(name: "aux2", value: aux2On ? "on" : "off")
    sendEvent(name: "spaStatus", value: "${heatMode}\n${isHeating ? "heating to ${targetTemperature}°" : "not heating"}")
}

def fetchChild(createIfDoesntExist, String type, String name, Integer balboaApiButtonNumber = 0) {
    String thisId = device.id
    def childDeviceName = "${thisId}-${name}"
    logMessage(2, "childDeviceName: '${childDeviceName}")
    
    def cd = getChildDevice(childDeviceName)
    if (!cd && createIfDoesntExist) {
        def driverName = "${DEVICE_NAME_PREFIX} ${type}"
                
        logMessage(3, "Adding Child Device. Driver: '${driverName}', Name: '${childDeviceName}'")
        cd = addChildDevice(NAMESPACE, driverName, childDeviceName, [name: "${device.displayName} {$name}", isComponent: true])
        
        // Switches will need to know their respective Balboa API Button IDs
        if (type == "Switch" && balboaApiButtonNumber > 0) {
            cd.setBalboaAPIButtonNumber(balboaApiButtonNumber)
        }
    }
    return cd
}

void refresh() {
    parent.pollChildren()
}
