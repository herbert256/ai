import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    id: root
    color: Style.background

    // ---- Local state ----
    property var messages: []           // Array of {id, role, content, timestamp}
    property string selectedProviderId: ""
    property string selectedModel: ""
    property bool isStreaming: false
    property string streamBuffer: ""
    property string currentSessionId: ""
    property bool systemPromptExpanded: false
    property bool parametersExpanded: false

    // Provider / model lists populated from viewModel
    property var providers: []
    property var modelList: []

    Component.onCompleted: {
        providers = viewModel.getProviders()
        if (providers.length > 0) {
            selectedProviderId = providers[0].id
            modelList = viewModel.getProviderModels(selectedProviderId)
            if (modelList.length > 0)
                selectedModel = modelList[0]
        }
    }

    // ---- ViewModel signal connections ----
    Connections {
        target: viewModel

        function onChatStreamChunk(text) {
            if (!root.isStreaming) return
            root.streamBuffer += text
            // Update last assistant message in-place
            if (root.messages.length > 0 &&
                root.messages[root.messages.length - 1].role === "assistant") {
                var msgs = root.messages.slice()
                msgs[msgs.length - 1].content = root.streamBuffer
                root.messages = msgs
                messageList.positionViewAtEnd()
            }
        }

        function onChatStreamFinished() {
            root.isStreaming = false
            root.streamBuffer = ""
        }

        function onChatStreamError(error) {
            root.isStreaming = false
            root.streamBuffer = ""
            if (root.messages.length > 0 &&
                root.messages[root.messages.length - 1].role === "assistant") {
                var msgs = root.messages.slice()
                msgs[msgs.length - 1].content = "Error: " + error
                root.messages = msgs
            }
        }

        function onChatResponse(text) {
            root.isStreaming = false
            root.streamBuffer = ""
            var msgs = root.messages.slice()
            msgs.push({
                id: Date.now().toString(),
                role: "assistant",
                content: text,
                timestamp: new Date().toISOString()
            })
            root.messages = msgs
            messageList.positionViewAtEnd()
        }

        function onModelsLoaded(providerId, models) {
            if (providerId === root.selectedProviderId) {
                root.modelList = models
                if (models.length > 0 && root.selectedModel === "")
                    root.selectedModel = models[0]
            }
        }
    }

    // ---- Helper functions ----
    function sendMessage() {
        var text = inputField.text.trim()
        if (text === "" || isStreaming) return

        // Add user message
        var msgs = messages.slice()
        msgs.push({
            id: Date.now().toString(),
            role: "user",
            content: text,
            timestamp: new Date().toISOString()
        })

        // Add placeholder assistant message for streaming
        msgs.push({
            id: (Date.now() + 1).toString(),
            role: "assistant",
            content: "",
            timestamp: new Date().toISOString()
        })

        messages = msgs
        inputField.text = ""
        isStreaming = true
        streamBuffer = ""

        // Build messages array for API
        var apiMessages = []
        for (var i = 0; i < messages.length - 1; i++) {  // exclude empty placeholder
            apiMessages.push({
                role: messages[i].role,
                content: messages[i].content
            })
        }

        // Build params
        var params = {}
        if (systemPromptField.text.trim() !== "")
            params.systemPrompt = systemPromptField.text.trim()
        var tempVal = parseFloat(temperatureField.text)
        if (!isNaN(tempVal))
            params.temperature = tempVal
        var maxTokVal = parseInt(maxTokensField.text)
        if (!isNaN(maxTokVal) && maxTokVal > 0)
            params.maxTokens = maxTokVal

        viewModel.sendChatStream(selectedProviderId, selectedModel, apiMessages, params)
        messageList.positionViewAtEnd()
    }

    function clearChat() {
        messages = []
        streamBuffer = ""
        isStreaming = false
        currentSessionId = ""
    }

    function newChat() {
        saveCurrentSession()
        clearChat()
    }

    function saveCurrentSession() {
        if (messages.length === 0) return
        var session = {
            id: currentSessionId !== "" ? currentSessionId : "",
            providerId: selectedProviderId,
            model: selectedModel,
            messages: messages,
            parameters: {
                systemPrompt: systemPromptField.text,
                temperature: parseFloat(temperatureField.text) || undefined,
                maxTokens: parseInt(maxTokensField.text) || undefined
            }
        }
        viewModel.saveChatSession(session)
    }

    function formatTime(isoString) {
        if (!isoString) return ""
        var d = new Date(isoString)
        return d.toLocaleTimeString(Qt.locale(), "HH:mm")
    }

    // ---- Layout ----
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 16
        spacing: 0

        // Title bar
        RowLayout {
            Layout.fillWidth: true
            Layout.bottomMargin: 12
            spacing: 12

            Text {
                text: "Chat"
                font.pixelSize: 22
                font.bold: true
                color: Style.primary
            }

            Item { Layout.fillWidth: true }

            Button {
                text: "New Chat"
                font.pixelSize: 12
                onClicked: root.newChat()
                background: Rectangle {
                    color: Style.primary
                    radius: 6
                    implicitWidth: 90
                    implicitHeight: 32
                }
                contentItem: Text {
                    text: parent.text
                    color: "#FFFFFF"
                    font.pixelSize: 12
                    font.bold: true
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
            }

            Button {
                text: "Clear"
                font.pixelSize: 12
                onClicked: root.clearChat()
                background: Rectangle {
                    color: Style.fieldBackground
                    border.color: Style.fieldBorder
                    border.width: 1
                    radius: 6
                    implicitWidth: 70
                    implicitHeight: 32
                }
                contentItem: Text {
                    text: parent.text
                    color: Style.textPrimary
                    font.pixelSize: 12
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
            }
        }

        // Provider / Model selectors
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: selectorRow.implicitHeight + 24
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8

            RowLayout {
                id: selectorRow
                anchors.fill: parent
                anchors.margins: 12
                spacing: 12

                Text {
                    text: "Provider"
                    font.pixelSize: 12
                    color: Style.textSecondary
                }

                ComboBox {
                    id: providerCombo
                    Layout.preferredWidth: 200
                    model: {
                        var names = []
                        for (var i = 0; i < root.providers.length; i++)
                            names.push(root.providers[i].displayName || root.providers[i].id)
                        return names
                    }
                    onCurrentIndexChanged: {
                        if (currentIndex >= 0 && currentIndex < root.providers.length) {
                            root.selectedProviderId = root.providers[currentIndex].id
                            root.modelList = viewModel.getProviderModels(root.selectedProviderId)
                            if (root.modelList.length > 0) {
                                root.selectedModel = root.modelList[0]
                                modelCombo.currentIndex = 0
                            }
                        }
                    }
                    background: Rectangle {
                        color: Style.fieldBackground
                        border.color: Style.fieldBorder
                        border.width: 1
                        radius: 4
                        implicitHeight: 30
                    }
                    contentItem: Text {
                        text: providerCombo.displayText
                        color: Style.textPrimary
                        font.pixelSize: 12
                        verticalAlignment: Text.AlignVCenter
                        leftPadding: 8
                    }
                    popup.background: Rectangle {
                        color: Style.cardBackground
                        border.color: Style.cardBorder
                        border.width: 1
                        radius: 4
                    }
                    delegate: ItemDelegate {
                        width: providerCombo.width
                        contentItem: Text {
                            text: modelData
                            color: Style.textPrimary
                            font.pixelSize: 12
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            color: highlighted ? Style.surfaceVariant : Style.cardBackground
                        }
                        highlighted: providerCombo.highlightedIndex === index
                    }
                }

                Text {
                    text: "Model"
                    font.pixelSize: 12
                    color: Style.textSecondary
                }

                ComboBox {
                    id: modelCombo
                    Layout.fillWidth: true
                    model: root.modelList
                    onCurrentIndexChanged: {
                        if (currentIndex >= 0 && currentIndex < root.modelList.length)
                            root.selectedModel = root.modelList[currentIndex]
                    }
                    background: Rectangle {
                        color: Style.fieldBackground
                        border.color: Style.fieldBorder
                        border.width: 1
                        radius: 4
                        implicitHeight: 30
                    }
                    contentItem: Text {
                        text: modelCombo.displayText
                        color: Style.textPrimary
                        font.pixelSize: 12
                        verticalAlignment: Text.AlignVCenter
                        leftPadding: 8
                    }
                    popup.background: Rectangle {
                        color: Style.cardBackground
                        border.color: Style.cardBorder
                        border.width: 1
                        radius: 4
                    }
                    delegate: ItemDelegate {
                        width: modelCombo.width
                        contentItem: Text {
                            text: modelData
                            color: Style.textPrimary
                            font.pixelSize: 12
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            color: highlighted ? Style.surfaceVariant : Style.cardBackground
                        }
                        highlighted: modelCombo.highlightedIndex === index
                    }
                }
            }
        }

        // Collapsible system prompt
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: systemPromptExpanded ? systemPromptCol.implicitHeight + 24 : 36
            Layout.topMargin: 8
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
            clip: true

            Behavior on Layout.preferredHeight {
                NumberAnimation { duration: 200; easing.type: Easing.OutCubic }
            }

            ColumnLayout {
                id: systemPromptCol
                anchors.fill: parent
                anchors.margins: 12
                spacing: 8

                RowLayout {
                    Layout.fillWidth: true

                    Text {
                        text: (systemPromptExpanded ? "\u25BC" : "\u25B6") + "  System Prompt"
                        font.pixelSize: 12
                        font.bold: true
                        color: Style.textSecondary
                    }

                    Item { Layout.fillWidth: true }

                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: systemPromptExpanded = !systemPromptExpanded
                    }
                }

                TextField {
                    id: systemPromptField
                    visible: systemPromptExpanded
                    Layout.fillWidth: true
                    placeholderText: "Optional system prompt..."
                    placeholderTextColor: Style.textHint
                    color: Style.textPrimary
                    font.pixelSize: 12
                    wrapMode: TextInput.Wrap
                    background: Rectangle {
                        color: Style.fieldBackground
                        border.color: Style.fieldBorder
                        border.width: 1
                        radius: 4
                        implicitHeight: 60
                    }
                }
            }
        }

        // Collapsible parameters
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: parametersExpanded ? paramsCol.implicitHeight + 24 : 36
            Layout.topMargin: 4
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
            clip: true

            Behavior on Layout.preferredHeight {
                NumberAnimation { duration: 200; easing.type: Easing.OutCubic }
            }

            ColumnLayout {
                id: paramsCol
                anchors.fill: parent
                anchors.margins: 12
                spacing: 8

                RowLayout {
                    Layout.fillWidth: true

                    Text {
                        text: (parametersExpanded ? "\u25BC" : "\u25B6") + "  Parameters"
                        font.pixelSize: 12
                        font.bold: true
                        color: Style.textSecondary
                    }

                    Item { Layout.fillWidth: true }

                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: parametersExpanded = !parametersExpanded
                    }
                }

                RowLayout {
                    visible: parametersExpanded
                    Layout.fillWidth: true
                    spacing: 16

                    Text {
                        text: "Temperature"
                        font.pixelSize: 12
                        color: Style.textSecondary
                    }

                    TextField {
                        id: temperatureField
                        Layout.preferredWidth: 80
                        text: "0.7"
                        color: Style.textPrimary
                        font.pixelSize: 12
                        horizontalAlignment: Text.AlignHCenter
                        validator: DoubleValidator { bottom: 0.0; top: 2.0; decimals: 2 }
                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 4
                            implicitHeight: 30
                        }
                    }

                    Text {
                        text: "Max Tokens"
                        font.pixelSize: 12
                        color: Style.textSecondary
                    }

                    TextField {
                        id: maxTokensField
                        Layout.preferredWidth: 100
                        text: "4096"
                        color: Style.textPrimary
                        font.pixelSize: 12
                        horizontalAlignment: Text.AlignHCenter
                        validator: IntValidator { bottom: 1; top: 100000 }
                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 4
                            implicitHeight: 30
                        }
                    }
                }
            }
        }

        // Message list
        Rectangle {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.topMargin: 8
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
            clip: true

            ListView {
                id: messageList
                anchors.fill: parent
                anchors.margins: 8
                spacing: 8
                model: root.messages.length
                clip: true
                boundsBehavior: Flickable.StopAtBounds

                ScrollBar.vertical: ScrollBar {
                    policy: ScrollBar.AsNeeded
                }

                delegate: Item {
                    id: msgDelegate
                    width: messageList.width
                    height: msgBubble.height + 4

                    property var msg: root.messages[index]
                    property bool isUser: msg.role === "user"
                    property bool isSystem: msg.role === "system"

                    Rectangle {
                        id: msgBubble
                        width: Math.min(msgContent.implicitWidth + 32, parent.width * 0.75)
                        height: msgContent.implicitHeight + 28
                        color: msgDelegate.isUser ? Style.primary
                             : msgDelegate.isSystem ? Style.surfaceVariant
                             : Style.cardBackground
                        border.color: msgDelegate.isUser ? "transparent" : Style.cardBorder
                        border.width: msgDelegate.isUser ? 0 : 1
                        radius: 12
                        anchors.right: msgDelegate.isUser ? parent.right : undefined
                        anchors.left: msgDelegate.isUser ? undefined : parent.left

                        ColumnLayout {
                            id: msgContent
                            anchors.fill: parent
                            anchors.margins: 12
                            spacing: 4

                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 8

                                Text {
                                    text: msgDelegate.isUser ? "You"
                                        : msgDelegate.isSystem ? "System"
                                        : "Assistant"
                                    font.pixelSize: 10
                                    font.bold: true
                                    color: msgDelegate.isUser ? "#FFFFFFCC"
                                         : Style.textSecondary
                                }

                                Item { Layout.fillWidth: true }

                                Text {
                                    text: root.formatTime(msgDelegate.msg.timestamp)
                                    font.pixelSize: 10
                                    color: msgDelegate.isUser ? "#FFFFFF99"
                                         : Style.textTertiary
                                }
                            }

                            Text {
                                text: msgDelegate.msg.content
                                font.pixelSize: 13
                                color: msgDelegate.isUser ? "#FFFFFF" : Style.textPrimary
                                wrapMode: Text.Wrap
                                Layout.fillWidth: true
                                textFormat: Text.PlainText
                            }
                        }
                    }
                }

                // Empty state
                Text {
                    visible: root.messages.length === 0
                    anchors.centerIn: parent
                    text: "Start a conversation by typing a message below."
                    font.pixelSize: 14
                    color: Style.textHint
                }
            }
        }

        // Streaming indicator
        RowLayout {
            Layout.fillWidth: true
            Layout.topMargin: 4
            Layout.preferredHeight: 20
            spacing: 6
            visible: root.isStreaming

            Text {
                text: "Receiving"
                font.pixelSize: 11
                color: Style.textSecondary
            }

            Row {
                spacing: 4
                Repeater {
                    model: 3
                    Rectangle {
                        width: 6; height: 6; radius: 3
                        color: Style.primary
                        opacity: 0.3

                        SequentialAnimation on opacity {
                            loops: Animation.Infinite
                            PauseAnimation { duration: index * 200 }
                            NumberAnimation { to: 1.0; duration: 400 }
                            NumberAnimation { to: 0.3; duration: 400 }
                            PauseAnimation { duration: (2 - index) * 200 }
                        }
                    }
                }
            }

            Item { Layout.fillWidth: true }

            Button {
                text: "Stop"
                onClicked: {
                    viewModel.stopChatStream()
                    root.isStreaming = false
                }
                background: Rectangle {
                    color: Style.error
                    radius: 4
                    implicitWidth: 60
                    implicitHeight: 24
                }
                contentItem: Text {
                    text: parent.text
                    color: "#FFFFFF"
                    font.pixelSize: 11
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                }
            }
        }

        // Input area
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 52
            Layout.topMargin: 8
            color: Style.cardBackground
            border.color: inputField.activeFocus ? Style.primary : Style.cardBorder
            border.width: 1
            radius: 8

            RowLayout {
                anchors.fill: parent
                anchors.margins: 8
                spacing: 8

                TextField {
                    id: inputField
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    placeholderText: "Type your message..."
                    placeholderTextColor: Style.textHint
                    color: Style.textPrimary
                    font.pixelSize: 13
                    enabled: !root.isStreaming
                    background: Rectangle { color: "transparent" }
                    onAccepted: root.sendMessage()
                }

                Button {
                    Layout.preferredWidth: 72
                    Layout.preferredHeight: 36
                    enabled: inputField.text.trim() !== "" && !root.isStreaming
                    onClicked: root.sendMessage()
                    background: Rectangle {
                        color: parent.enabled ? Style.primary : Style.fieldBackground
                        radius: 6
                    }
                    contentItem: Text {
                        text: "Send"
                        color: parent.enabled ? "#FFFFFF" : Style.textHint
                        font.pixelSize: 13
                        font.bold: true
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                    }
                }
            }
        }
    }
}
