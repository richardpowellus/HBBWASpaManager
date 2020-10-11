/*
 *  Hubitat BWA Spa Manager
 *  -> Thermostat Device Driver
 *
 *  Copyright 2020 Richard Powell
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
 *  0.0.1       2020-10-11      First release
 *
 */

import groovy.transform.Field
import groovy.time.TimeCategory

@Field static int LOG_LEVEL = 3

@Field static String NAMESPACE = "richardpowellus"

@Field static String THERMOSTAT_CHILD_DEVICE_NAME_PREFIX = "HB BWA SPA Thermostat"

metadata {
    definition (name: THERMOSTAT_CHILD_DEVICE_NAME_PREFIX, namespace: NAMESPACE, author: "Richard Powell") {
        capability "Thermostat"
        capability "Refresh"
        
        attribute "supportedThermostatFanModes", "enum", ["circulate"]
        attribute "supportedThermostatModes", "enum", ["off", "heat"]
        attribute "thermostatFanMode", "string"
        attribute "thermostatOperatingState", "string"

        command "heat"
        command "setThermostatMode"
        command "setHeatingSetpoint"
        command "getTemperatureRange"
        
        preferences {
            input "defaultOnTemperature", "number", title: "Default Temperature When Turned On", range: getTemperatureRange()
        }
    }
}

void sendEvents(List<Map> events) {
    events.each {
        sendEvent(name: it.name, value: it.value)
    }
}

void sendEventsWithStateChange(List<Map> events) {
    events.each {
        sendEvent(name: it.name, value: it.value, isStateChange: true)
    }
}

void sendEventsWithUnits(List<Map> events) {
    events.each {
        sendEvent(name: it.name, value: it.value, unit: it.unit)
    }
}

void installed() {
    sendEventsWithStateChange([
        [name:"supportedThermostatFanModes", value: ["circulate"]],
        [name:"supportedThermostatModes", value: ["off", "heat"]],
        [name:"thermostatFanMode", value: "circulate"]
    ])
}

void heat() {
    setThermostatMode("heat")
}

void setThermostatMode(mode) {
    // TODO: Throw an exception if we're asked to set the thermostat to a mode we don't support (e.g. "cool")
    sendEvent([name: "thermostatMode", value: mode])
}

void setHeatingSetpoint(setpoint) {
    sendEvent(name: "heatingSetpoint", value: setpoint)
    parent?.sendCommand("SetTemp", device.currentValue("temperatureScale") == "C" ? setpoint * 2 : setpoint)
}

def getTemperatureRange() {
    return "(26.5..104)"
}

def refresh() {
    parent?.refresh()
}
