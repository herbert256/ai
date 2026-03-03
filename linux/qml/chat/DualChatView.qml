import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    id: root
    color: Style.background

    // ---- State ----
    property var providers: []
    property var modelList1: []
    property var modelList2: []

    property string provider1Id: ""
    property string provider2Id: ""
    property string model1: ""
    property string model2: ""
    property string subject: ""
    property int interactionCount: 10
    property string firstPrompt: "Let's talk about %subject%"
    property string secondPrompt: "What do you think about: %answer%"

    property var panel1Messages: []
    property var panel2Messages: []

    property bool isRunning: false
    property int currentTurn: 0
    property int activePanel: 1        // which panel is currently receiving (1 or 2)
    property string streamBuffer: ""
    property bool configExpanded: true

    Component.onCompleted: {
        providers = viewModel.getProviders()
        if (providers.length > 0) {
            provider1Id = providers[0].id
            provider2Id = providers.length > 1 ? providers[1].id : providers[0].id
            modelList1 = viewModel.getProviderModels(provider1Id)
            modelList2 = viewModel.getProviderModels(provider2Id)
            if (modelList1.length > 0) model1 = modelList1[0]
            if (modelList2.length > 0) model2 = modelList2[0]
        }
    }

    // ---- Signal handling ----
    Connections {
        target: viewModel

        function onChatStreamChunk(text) {
            if (!root.isRunning) return
            root.streamBuffer += text
            if (root.activePanel === 1) {
                var msgs1 = root.panel1Messages.slice()
                if (msgs1.length > 0)
                    msgs1[msgs1.length - 1].content = root.streamBuffer
                root.panel1Messages = msgs1
            } else {
                var msgs2 = root.panel2Messages.slice()
                if (msgs2.length > 0)
                    msgs2[msgs2.length - 1].content = root.streamBuffer
                root.panel2Messages = msgs2
            }
        }

        function onChatStreamFinished() {
            if (!root.isRunning) return
            var lastResponse = root.streamBuffer
            root.streamBuffer = ""
            root.currentTurn++

            if (root.currentTurn >= root.interactionCount) {
                root.isRunning = false
                return
            }

            // Switch panels and send next message
            if (root.activePanel === 1) {
                root.activePanel = 2
                sendToPanel2(lastResponse)
            } else {
                root.activePanel = 1
                sendToPanel1(lastResponse)
            }
        }

        function onChatStreamError(error) {
            root.isRunning = false
            root.streamBuffer = ""
            // Append error to active panel
            if (root.activePanel === 1) {
                var msgs1 = root.panel1Messages.slice()
                if (msgs1.length > 0)
                    msgs1[msgs1.length - 1].content = "Error: " + error
                root.panel1Messages = msgs1
            } else {
                var msgs2 = root.panel2Messages.slice()
                if (msgs2.length > 0)
                    msgs2[msgs2.length - 1].content = "Error: " + error
                root.panel2Messages = msgs2
            }
        }

        function onModelsLoaded(providerId, models) {
            if (providerId === root.provider1Id) {
                root.modelList1 = models
                if (models.length > 0 && root.model1 === "")
                    root.model1 = models[0]
            }
            if (providerId === root.provider2Id) {
                root.modelList2 = models
                if (models.length > 0 && root.model2 === "")
                    root.model2 = models[0]
            }
        }
    }

    // ---- Functions ----
    function startConversation() {
        if (subject.trim() === "" || isRunning) return

        panel1Messages = []
        panel2Messages = []
        currentTurn = 0
        isRunning = true
        activePanel = 1
        streamBuffer = ""

        // Send first prompt to panel 1
        var prompt = firstPrompt.replace("%subject%", subject)
        sendToPanel1(prompt)
    }

    function stopConversation() {
        isRunning = false
        viewModel.stopChatStream()
        streamBuffer = ""
    }

    function sendToPanel1(promptText) {
        // Add the prompt as a "user" message in panel 1 (coming from model 2)
        var msgs = panel1Messages.slice()
        msgs.push({
            id: Date.now().toString(),
            role: "user",
            content: promptText,
            timestamp: new Date().toISOString()
        })
        // Placeholder for assistant response
        msgs.push({
            id: (Date.now() + 1).toString(),
            role: "assistant",
            content: "",
            timestamp: new Date().toISOString()
        })
        panel1Messages = msgs

        // Build API messages from panel 1 history
        var apiMessages = buildApiMessages(panel1Messages)
        streamBuffer = ""
        viewModel.sendChatStream(provider1Id, model1, apiMessages, {})
    }

    function sendToPanel2(lastAnswer) {
        var prompt = secondPrompt.replace("%answer%", lastAnswer)
        prompt = prompt.replace("%subject%", subject)

        var msgs = panel2Messages.slice()
        msgs.push({
            id: Date.now().toString(),
            role: "user",
            content: prompt,
            timestamp: new Date().toISOString()
        })
        msgs.push({
            id: (Date.now() + 1).toString(),
            role: "assistant",
            content: "",
            timestamp: new Date().toISOString()
        })
        panel2Messages = msgs

        var apiMessages = buildApiMessages(panel2Messages)
        streamBuffer = ""
        viewModel.sendChatStream(provider2Id, model2, apiMessages, {})
    }

    function buildApiMessages(msgs) {
        var result = []
        // Exclude last empty placeholder
        var count = msgs.length
        if (count > 0 && msgs[count - 1].content === "")
            count--
        for (var i = 0; i < count; i++) {
            result.push({ role: msgs[i].role, content: msgs[i].content })
        }
        return result
    }

    function providerDisplayName(providerId) {
        for (var i = 0; i < providers.length; i++) {
            if (providers[i].id === providerId)
                return providers[i].displayName || providers[i].id
        }
        return providerId
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
                text: "Dual Chat"
                font.pixelSize: 22
                font.bold: true
                color: Style.primary
            }

            Text {
                visible: root.isRunning
                text: "Turn " + (root.currentTurn + 1) + " / " + root.interactionCount
                font.pixelSize: 12
                color: Style.textSecondary
            }

            Item { Layout.fillWidth: true }

            Button {
                text: root.isRunning ? "Stop" : "Start"
                enabled: !root.isRunning ? (root.subject.trim() !== "") : true
                onClicked: root.isRunning ? root.stopConversation() : root.startConversation()
                background: Rectangle {
                    color: root.isRunning ? Style.error : Style.primary
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
        }

        // Collapsible configuration section
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: configExpanded ? configCol.implicitHeight + 24 : 36
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
            clip: true

            Behavior on Layout.preferredHeight {
                NumberAnimation { duration: 200; easing.type: Easing.OutCubic }
            }

            ColumnLayout {
                id: configCol
                anchors.fill: parent
                anchors.margins: 12
                spacing: 10

                // Header
                RowLayout {
                    Layout.fillWidth: true

                    Text {
                        text: (configExpanded ? "\u25BC" : "\u25B6") + "  Configuration"
                        font.pixelSize: 12
                        font.bold: true
                        color: Style.textSecondary
                    }

                    Item { Layout.fillWidth: true }

                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: configExpanded = !configExpanded
                    }
                }

                // Model 1 selector
                RowLayout {
                    visible: configExpanded
                    Layout.fillWidth: true
                    spacing: 12

                    Rectangle {
                        width: 12; height: 12; radius: 6
                        color: Style.primary
                    }

                    Text {
                        text: "Model 1"
                        font.pixelSize: 12
                        font.bold: true
                        color: Style.textPrimary
                        Layout.preferredWidth: 60
                    }

                    ComboBox {
                        id: provider1Combo
                        Layout.preferredWidth: 180
                        model: {
                            var names = []
                            for (var i = 0; i < root.providers.length; i++)
                                names.push(root.providers[i].displayName || root.providers[i].id)
                            return names
                        }
                        onCurrentIndexChanged: {
                            if (currentIndex >= 0 && currentIndex < root.providers.length) {
                                root.provider1Id = root.providers[currentIndex].id
                                root.modelList1 = viewModel.getProviderModels(root.provider1Id)
                                if (root.modelList1.length > 0) {
                                    root.model1 = root.modelList1[0]
                                    model1Combo.currentIndex = 0
                                }
                            }
                        }
                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 4
                            implicitHeight: 28
                        }
                        contentItem: Text {
                            text: provider1Combo.displayText
                            color: Style.textPrimary
                            font.pixelSize: 12
                            verticalAlignment: Text.AlignVCenter
                            leftPadding: 8
                        }
                        popup.background: Rectangle {
                            color: Style.cardBackground
                            border.color: Style.cardBorder
                            radius: 4
                        }
                        delegate: ItemDelegate {
                            width: provider1Combo.width
                            contentItem: Text {
                                text: modelData; color: Style.textPrimary; font.pixelSize: 12
                                verticalAlignment: Text.AlignVCenter
                            }
                            background: Rectangle {
                                color: highlighted ? Style.surfaceVariant : Style.cardBackground
                            }
                            highlighted: provider1Combo.highlightedIndex === index
                        }
                    }

                    ComboBox {
                        id: model1Combo
                        Layout.fillWidth: true
                        model: root.modelList1
                        onCurrentIndexChanged: {
                            if (currentIndex >= 0 && currentIndex < root.modelList1.length)
                                root.model1 = root.modelList1[currentIndex]
                        }
                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 4
                            implicitHeight: 28
                        }
                        contentItem: Text {
                            text: model1Combo.displayText
                            color: Style.textPrimary
                            font.pixelSize: 12
                            verticalAlignment: Text.AlignVCenter
                            leftPadding: 8
                        }
                        popup.background: Rectangle {
                            color: Style.cardBackground
                            border.color: Style.cardBorder
                            radius: 4
                        }
                        delegate: ItemDelegate {
                            width: model1Combo.width
                            contentItem: Text {
                                text: modelData; color: Style.textPrimary; font.pixelSize: 12
                                verticalAlignment: Text.AlignVCenter
                            }
                            background: Rectangle {
                                color: highlighted ? Style.surfaceVariant : Style.cardBackground
                            }
                            highlighted: model1Combo.highlightedIndex === index
                        }
                    }
                }

                // Model 2 selector
                RowLayout {
                    visible: configExpanded
                    Layout.fillWidth: true
                    spacing: 12

                    Rectangle {
                        width: 12; height: 12; radius: 6
                        color: Style.success
                    }

                    Text {
                        text: "Model 2"
                        font.pixelSize: 12
                        font.bold: true
                        color: Style.textPrimary
                        Layout.preferredWidth: 60
                    }

                    ComboBox {
                        id: provider2Combo
                        Layout.preferredWidth: 180
                        currentIndex: root.providers.length > 1 ? 1 : 0
                        model: {
                            var names = []
                            for (var i = 0; i < root.providers.length; i++)
                                names.push(root.providers[i].displayName || root.providers[i].id)
                            return names
                        }
                        onCurrentIndexChanged: {
                            if (currentIndex >= 0 && currentIndex < root.providers.length) {
                                root.provider2Id = root.providers[currentIndex].id
                                root.modelList2 = viewModel.getProviderModels(root.provider2Id)
                                if (root.modelList2.length > 0) {
                                    root.model2 = root.modelList2[0]
                                    model2Combo.currentIndex = 0
                                }
                            }
                        }
                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 4
                            implicitHeight: 28
                        }
                        contentItem: Text {
                            text: provider2Combo.displayText
                            color: Style.textPrimary
                            font.pixelSize: 12
                            verticalAlignment: Text.AlignVCenter
                            leftPadding: 8
                        }
                        popup.background: Rectangle {
                            color: Style.cardBackground
                            border.color: Style.cardBorder
                            radius: 4
                        }
                        delegate: ItemDelegate {
                            width: provider2Combo.width
                            contentItem: Text {
                                text: modelData; color: Style.textPrimary; font.pixelSize: 12
                                verticalAlignment: Text.AlignVCenter
                            }
                            background: Rectangle {
                                color: highlighted ? Style.surfaceVariant : Style.cardBackground
                            }
                            highlighted: provider2Combo.highlightedIndex === index
                        }
                    }

                    ComboBox {
                        id: model2Combo
                        Layout.fillWidth: true
                        model: root.modelList2
                        onCurrentIndexChanged: {
                            if (currentIndex >= 0 && currentIndex < root.modelList2.length)
                                root.model2 = root.modelList2[currentIndex]
                        }
                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 4
                            implicitHeight: 28
                        }
                        contentItem: Text {
                            text: model2Combo.displayText
                            color: Style.textPrimary
                            font.pixelSize: 12
                            verticalAlignment: Text.AlignVCenter
                            leftPadding: 8
                        }
                        popup.background: Rectangle {
                            color: Style.cardBackground
                            border.color: Style.cardBorder
                            radius: 4
                        }
                        delegate: ItemDelegate {
                            width: model2Combo.width
                            contentItem: Text {
                                text: modelData; color: Style.textPrimary; font.pixelSize: 12
                                verticalAlignment: Text.AlignVCenter
                            }
                            background: Rectangle {
                                color: highlighted ? Style.surfaceVariant : Style.cardBackground
                            }
                            highlighted: model2Combo.highlightedIndex === index
                        }
                    }
                }

                // Subject & interaction count
                RowLayout {
                    visible: configExpanded
                    Layout.fillWidth: true
                    spacing: 12

                    Text {
                        text: "Subject"
                        font.pixelSize: 12
                        color: Style.textSecondary
                    }

                    TextField {
                        id: subjectField
                        Layout.fillWidth: true
                        text: root.subject
                        placeholderText: "What should they discuss?"
                        placeholderTextColor: Style.textHint
                        color: Style.textPrimary
                        font.pixelSize: 12
                        onTextChanged: root.subject = text
                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 4
                            implicitHeight: 30
                        }
                    }

                    Text {
                        text: "Turns"
                        font.pixelSize: 12
                        color: Style.textSecondary
                    }

                    SpinBox {
                        id: turnsSpinBox
                        value: root.interactionCount
                        from: 1
                        to: 50
                        editable: true
                        onValueChanged: root.interactionCount = value

                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 4
                            implicitWidth: 100
                            implicitHeight: 30
                        }
                        contentItem: TextInput {
                            text: turnsSpinBox.textFromValue(turnsSpinBox.value, turnsSpinBox.locale)
                            color: Style.textPrimary
                            font.pixelSize: 12
                            horizontalAlignment: Qt.AlignHCenter
                            verticalAlignment: Qt.AlignVCenter
                            readOnly: !turnsSpinBox.editable
                            validator: turnsSpinBox.validator
                            inputMethodHints: Qt.ImhFormattedNumbersOnly
                        }
                        up.indicator: Rectangle {
                            x: turnsSpinBox.mirrored ? 0 : parent.width - width
                            height: parent.height
                            implicitWidth: 24
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            Text {
                                text: "+"
                                font.pixelSize: 14
                                color: Style.textPrimary
                                anchors.centerIn: parent
                            }
                        }
                        down.indicator: Rectangle {
                            x: turnsSpinBox.mirrored ? parent.width - width : 0
                            height: parent.height
                            implicitWidth: 24
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            Text {
                                text: "-"
                                font.pixelSize: 14
                                color: Style.textPrimary
                                anchors.centerIn: parent
                            }
                        }
                    }
                }

                // Prompt templates
                RowLayout {
                    visible: configExpanded
                    Layout.fillWidth: true
                    spacing: 12

                    Text {
                        text: "1st Prompt"
                        font.pixelSize: 12
                        color: Style.textSecondary
                        Layout.preferredWidth: 70
                    }

                    TextField {
                        Layout.fillWidth: true
                        text: root.firstPrompt
                        color: Style.textPrimary
                        font.pixelSize: 12
                        onTextChanged: root.firstPrompt = text
                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 4
                            implicitHeight: 28
                        }
                    }
                }

                RowLayout {
                    visible: configExpanded
                    Layout.fillWidth: true
                    spacing: 12

                    Text {
                        text: "2nd Prompt"
                        font.pixelSize: 12
                        color: Style.textSecondary
                        Layout.preferredWidth: 70
                    }

                    TextField {
                        Layout.fillWidth: true
                        text: root.secondPrompt
                        color: Style.textPrimary
                        font.pixelSize: 12
                        onTextChanged: root.secondPrompt = text
                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 4
                            implicitHeight: 28
                        }
                    }
                }
            }
        }

        // Dual chat panels
        SplitView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.topMargin: 8
            orientation: Qt.Horizontal

            // Panel 1
            Rectangle {
                SplitView.fillWidth: true
                SplitView.minimumWidth: 250
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8
                clip: true

                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 8
                    spacing: 4

                    // Panel header
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 6

                        Rectangle {
                            width: 10; height: 10; radius: 5
                            color: Style.primary
                        }

                        Text {
                            text: root.model1 || "Model 1"
                            font.pixelSize: 12
                            font.bold: true
                            color: Style.primary
                            elide: Text.ElideRight
                            Layout.fillWidth: true
                        }

                        Text {
                            text: root.providerDisplayName(root.provider1Id)
                            font.pixelSize: 10
                            color: Style.textTertiary
                        }

                        // Streaming indicator for panel 1
                        Row {
                            visible: root.isRunning && root.activePanel === 1
                            spacing: 3
                            Repeater {
                                model: 3
                                Rectangle {
                                    width: 5; height: 5; radius: 2.5
                                    color: Style.primary
                                    opacity: 0.3
                                    SequentialAnimation on opacity {
                                        loops: Animation.Infinite
                                        PauseAnimation { duration: index * 150 }
                                        NumberAnimation { to: 1.0; duration: 300 }
                                        NumberAnimation { to: 0.3; duration: 300 }
                                        PauseAnimation { duration: (2 - index) * 150 }
                                    }
                                }
                            }
                        }
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        height: 1
                        color: Style.cardBorder
                    }

                    ListView {
                        id: panel1List
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        spacing: 6
                        model: root.panel1Messages.length
                        clip: true
                        boundsBehavior: Flickable.StopAtBounds

                        ScrollBar.vertical: ScrollBar {
                            policy: ScrollBar.AsNeeded
                        }

                        onCountChanged: positionViewAtEnd()

                        delegate: Rectangle {
                            id: p1Delegate
                            width: panel1List.width
                            height: p1MsgCol.implicitHeight + 16
                            property var msg: root.panel1Messages[index]
                            property bool isUser: msg.role === "user"
                            color: isUser ? Qt.rgba(Style.primary.r, Style.primary.g, Style.primary.b, 0.1)
                                          : "transparent"
                            radius: 6

                            ColumnLayout {
                                id: p1MsgCol
                                anchors.fill: parent
                                anchors.margins: 8
                                spacing: 2

                                Text {
                                    text: p1Delegate.isUser ? "Model 2" : "Model 1"
                                    font.pixelSize: 10
                                    font.bold: true
                                    color: p1Delegate.isUser ? Style.success : Style.primary
                                }

                                Text {
                                    text: p1Delegate.msg.content
                                    font.pixelSize: 12
                                    color: Style.textPrimary
                                    wrapMode: Text.Wrap
                                    Layout.fillWidth: true
                                    textFormat: Text.PlainText
                                }
                            }
                        }

                        Text {
                            visible: root.panel1Messages.length === 0
                            anchors.centerIn: parent
                            text: "Model 1 messages"
                            font.pixelSize: 12
                            color: Style.textHint
                        }
                    }
                }
            }

            // Panel 2
            Rectangle {
                SplitView.fillWidth: true
                SplitView.minimumWidth: 250
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8
                clip: true

                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 8
                    spacing: 4

                    // Panel header
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 6

                        Rectangle {
                            width: 10; height: 10; radius: 5
                            color: Style.success
                        }

                        Text {
                            text: root.model2 || "Model 2"
                            font.pixelSize: 12
                            font.bold: true
                            color: Style.success
                            elide: Text.ElideRight
                            Layout.fillWidth: true
                        }

                        Text {
                            text: root.providerDisplayName(root.provider2Id)
                            font.pixelSize: 10
                            color: Style.textTertiary
                        }

                        // Streaming indicator for panel 2
                        Row {
                            visible: root.isRunning && root.activePanel === 2
                            spacing: 3
                            Repeater {
                                model: 3
                                Rectangle {
                                    width: 5; height: 5; radius: 2.5
                                    color: Style.success
                                    opacity: 0.3
                                    SequentialAnimation on opacity {
                                        loops: Animation.Infinite
                                        PauseAnimation { duration: index * 150 }
                                        NumberAnimation { to: 1.0; duration: 300 }
                                        NumberAnimation { to: 0.3; duration: 300 }
                                        PauseAnimation { duration: (2 - index) * 150 }
                                    }
                                }
                            }
                        }
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        height: 1
                        color: Style.cardBorder
                    }

                    ListView {
                        id: panel2List
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        spacing: 6
                        model: root.panel2Messages.length
                        clip: true
                        boundsBehavior: Flickable.StopAtBounds

                        ScrollBar.vertical: ScrollBar {
                            policy: ScrollBar.AsNeeded
                        }

                        onCountChanged: positionViewAtEnd()

                        delegate: Rectangle {
                            id: p2Delegate
                            width: panel2List.width
                            height: p2MsgCol.implicitHeight + 16
                            property var msg: root.panel2Messages[index]
                            property bool isUser: msg.role === "user"
                            color: isUser ? Qt.rgba(Style.success.r, Style.success.g, Style.success.b, 0.1)
                                          : "transparent"
                            radius: 6

                            ColumnLayout {
                                id: p2MsgCol
                                anchors.fill: parent
                                anchors.margins: 8
                                spacing: 2

                                Text {
                                    text: p2Delegate.isUser ? "Model 1" : "Model 2"
                                    font.pixelSize: 10
                                    font.bold: true
                                    color: p2Delegate.isUser ? Style.primary : Style.success
                                }

                                Text {
                                    text: p2Delegate.msg.content
                                    font.pixelSize: 12
                                    color: Style.textPrimary
                                    wrapMode: Text.Wrap
                                    Layout.fillWidth: true
                                    textFormat: Text.PlainText
                                }
                            }
                        }

                        Text {
                            visible: root.panel2Messages.length === 0
                            anchors.centerIn: parent
                            text: "Model 2 messages"
                            font.pixelSize: 12
                            color: Style.textHint
                        }
                    }
                }
            }
        }
    }
}
