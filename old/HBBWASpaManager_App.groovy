/*
 *  Hubitat BWA Spa Manager
 *  -> App
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
 *  1.0.0       2020-01-31      Updated icons and bumped version to match DTH version
 *  1.0.1b      2020-09-17      Modified to now work with Hubitat
 *
 */

import groovy.transform.Field

@Field static int LOG_LEVEL = 5

definition(
    name: "BWA Spa Manager",
    namespace: "richardpowellus",
    author: "Richard Powell",
    description: "Access and control your BWA Spa.",
    category: "Health & Wellness",
    iconUrl: "https://raw.githubusercontent.com/richardpowellus/HBBWASpaManager/master/images/hot-tub.png",
    iconX2Url: "https://raw.githubusercontent.com/richardpowellus/HBBWASpaManager/master/images/hot-tub.png",
    iconX3Url: "https://raw.githubusercontent.com/richardpowellus/HBBWASpaManager/master/images/hot-tub.png",
    singleInstance: false
) {
}

preferences {
    page(name: "mainPage")
    page(name: "authPage")
    page(name: "authResultPage")
}

def logMessage(level, message) {
    if (level >= LOG_LEVEL) {
        if (level < 3) {
            log.debug message
        } else {
            log.info message
        }
    }
}

def mainPage() {
        def spas = [:]
        // Get spas if we don't have them already
        if ((state.spas?.size()?:0) == 0 && state.token?.trim()) {
            getSpas()
        }
        logMessage(3, state.spas)
        if (state.spas) {
            spas = state.spas
            spas.sort { it.value }
        }
            
        dynamicPage(name: "mainPage", install: true, uninstall: true) {
            if (spas) {
                section("Select which Spas to use:") {
                    input(name: "spas", type: "enum", title: "Spas", required: false, multiple: true, options: spas)
                }
                section("How frequently do you want to poll the BWA cloud for changes? (Use a lower number if you care about trying to capture and respond to \"change\" events as they happen)") {
                    input(name: "pollingInterval", title: "Polling Interval (in Minutes)", type: "enum", required: false, multiple: false, defaultValue: 5, description: "5", options: ["1", "5", "10", "15", "30"])
                }
            }
            section("BWA Authentication") {
                href("authPage", title: "Cloud Authorization", description: "${state.credentialStatus ? state.credentialStatus+"\n" : ""}Click to enter BWA credentials")
            }
            section ("Name this instance of ${app.name}") {
                label name: "name", title: "Assign a name", required: false, defaultValue: app.name, description: app.name, submitOnChange: true
            }
        }
}

def authPage() {
    dynamicPage(name: "authPage", nextPage: "authResultPage", uninstall: false, install: false) {
        section("BWA Credentials") {
            input("username", "username", title: "User ID", description: "BWA User ID", required: true)
            input("password", "password", title: "Password", description: "BWA Password", required: true)
        }
    }
}

def authResultPage() {
    logMessage(3, "Attempting login with specified credentials...")
    
    doLogin()
    logMessage(3, state.loginResponse)
    
    // Check if login was successful
    if (state.token == null) {
        dynamicPage(name: "authResultPage", nextPage: "authPage", uninstall: false, install: false) {
            section("${state.loginResponse}") {
                paragraph ("Please check your credentials and try again.")
            }
        }
    } else {
        dynamicPage(name: "authResultPage", nextPage: "mainPage", uninstall: false, install: false) {
            section("${state.loginResponse}") {
                paragraph ("Please click next to continue setting up your spa.")
            }
        }
    }
}

boolean doLogin(){
    def loggedIn = false
    def resp = doCallout("POST", "/users/login", [username: username, password: password])
    
    switch (resp.status) {
        case 403:
            state.loginResponse = "Access forbidden"
            state.credentialStatus = "[Disconnected]"
            state.token = null
            state.spas = null
            break
        case 401:
            state.loginResponse = resp.data.message
            state.credentialStatus = "[Disconnected]"
            state.token = null
            state.spas = null
            break
        case 200:
            loggedIn = true
            state.loginResponse = "Logged in"
            state.token = resp.data.token
            state.credentialStatus = "[Connected]"
            state.loginDate = toStDateString(new Date())
            cacheSpas(resp.data.device)
            break
        default:
            logMessage(2, resp.data)
            state.loginResponse = "Login unsuccessful"
            state.credentialStatus = "[Disconnected]"
            state.token = null
            state.spas = null
            break
    }

    loggedIn
}

def reAuth() {
    if (!doLogin())
        doLogin() // timeout or other issue occurred, try one more time
}

// Get the list of spas (currently a 1:1 relationship with login)
def getSpas() {
    def data = doCallout("POST", "/users/login", [username: username, password: password]).data
    cacheSpas(data.device)
}

def cacheSpas(spa) {
    // save in state so we can re-use in settings
    def spas = [:]
    spas[[app.id, spa.device_id].join('.')] = "Spa " + spa.device_id[-8..-1]
    state.spas = spas
    state.device = spa
    spa
}

def doCallout(calloutMethod, urlPath, calloutBody) {
    doCallout(calloutMethod, urlPath, calloutBody, "json", null)
}

def doCallout(calloutMethod, urlPath, calloutBody, contentType) {
    doCallout(calloutMethod, urlPath, calloutBody, contentType, null)
}

def doCallout(calloutMethod, urlPath, calloutBody, contentType, queryParams) {
    logMessage(3, "\"${calloutMethod}\"-ing ${contentType} to \"${urlPath}\"")
    def content_type
    switch(contentType) {
        case "xml":
            content_type = "application/xml"
            break
        case "json":
        default:
            content_type = "application/json"
            break
    }
    def params = [
        uri: "https://bwgapi.balboawater.com/",
        path: "${urlPath}",
        query: queryParams,
        headers: [
            Authorization: state.token?.trim() ? "Bearer ${state.token as String}" : null
        ],
        requestContentType: content_type,
        body: calloutBody
    ]
    
    def result
    try {
        switch (calloutMethod) {
            case "GET":
                httpGet(params) { resp ->
                    result = resp
                }
                break
            case "PATCH":
                params.headers["x-http-method-override"] = "PATCH"
                // NOTE: break is purposefully missing so that it falls into the next case and "POST"s
            case "POST":
            	httpPost(params) { resp ->
                	result = resp
                }
                break
            default:
                log.error "unhandled method"
                return [error: "unhandled method"]
                break
        }
    } catch (groovyx.net.http.HttpResponseException e) {
    	log.debug e
        return e.response
    } catch (e) {
        log.error "Something went wrong: ${e}"
        return [error: e.message]
    }
    
    return result
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {
    // Not sure when tokens expire, but will get a new one every 24 hours just in case by scheduling to reauthorize every day
    if(state.loginDate?.trim()) schedule(parseStDate(state.loginDate), reAuth)

    def delete = getChildDevices().findAll { !settings.spas?.contains(it.deviceNetworkId) }
    delete.each {
        deleteChildDevice(it.deviceNetworkId)
    }
    
    def childDevices = []
    settings.spas.each {deviceId ->
        try {
            def childDevice = getChildDevice(deviceId)
            if(!childDevice) {
                logMessage(4, "Adding device: ${state.spas[deviceId]} [${deviceId}]")
                childDevice = addChildDevice("richardpowellus", "BWA Spa", deviceId, [label: state.spas[deviceId]])
                childDevice.configure()
                childDevice.parseDeviceData(state.device)
            }
            childDevices.add(childDevice)
        } catch (e) {
            log.error "Error creating device: ${e}"
        }
    }
    
    // set up polling only if we have child devices
    if(childDevices.size() > 0) {
        pollChildren()
        "runEvery${pollingInterval}Minute${pollingInterval != "1" ? 's' : ''}"("pollChildren")
    } else unschedule(pollChildren)
}

def pollChildren() {
    logMessage(3, "polling...")
    def devices = getChildDevices()
    devices.each {
        def deviceData = getPanelUpdate(it.currentValue("device_id"))
        it.parsePanelData(deviceData)
    }
}

// Get panel update
def getPanelUpdate(device_id) {
    logMessage(3, "getting panel update for ${device_id}")
    def resp = doCallout("POST", "/devices/sci", getXmlRequest(device_id, "PanelUpdate"), "xml")
    resp.data
}

def getXmlRequest(deviceId, fileName) {
    "<sci_request version=\"1.0\"><file_system cache=\"false\"><targets><device id=\"${deviceId}\"/></targets><commands><get_file path=\"${fileName}.txt\"/></commands></file_system></sci_request>"
}

def sendCommand(deviceId, targetName, data) {
    logMessage(3, "sending ${targetName}:${data} command for ${deviceId}")
    def resp = doCallout("POST", "/devices/sci", getXmlRequest(deviceId, targetName, data), "xml")
    resp.data
}

def getXmlRequest(deviceId, targetName, data) {
    "<sci_request version=\"1.0\"><data_service><targets><device id=\"${deviceId}\"/></targets><requests><device_request target_name=\"${targetName}\">${data}</device_request></requests></data_service></sci_request>"
}

def isoFormat() {
    "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
}

def toStDateString(date) {
    date.format(isoFormat())
}

def parseStDate(dateStr) {
    dateStr?.trim() ? timeToday(dateStr) : null
}
