import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

// Modal dialog for selecting agents, flocks, and swarms via a tabbed interface
Rectangle {
    id: root
    color: Qt.rgba(0, 0, 0, 0.75)

    property var initialAgentIds: ({})
    property var initialFlockIds: ({})
    property var initialSwarmIds: ({})

    // Internal working copies
    property var agentChecks: ({})
    property var flockChecks: ({})
    property var swarmChecks: ({})

    property int currentTab: 0  // 0=Agents, 1=Flocks, 2=Swarms

    signal accepted(var agents, var flocks, var swarms)
    signal cancelled()

    Component.onCompleted: {
        agentChecks = JSON.parse(JSON.stringify(initialAgentIds))
        flockChecks = JSON.parse(JSON.stringify(initialFlockIds))
        swarmChecks = JSON.parse(JSON.stringify(initialSwarmIds))
    }

    // Block background clicks
    MouseArea {
        anchors.fill: parent
        onClicked: {} // absorb
    }

    // Dialog card
    Rectangle {
        anchors.centerIn: parent
        width: Math.min(parent.width - 60, 650)
        height: Math.min(parent.height - 80, 550)
        color: Style.cardBackground
        border.color: Style.cardBorder
        border.width: 1
        radius: 12

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 20
            spacing: 12

            // Title
            Text {
                text: "Select AI Sources"
                font.pixelSize: 18
                font.bold: true
                color: Style.primary
            }

            // Tab bar
            RowLayout {
                Layout.fillWidth: true
                spacing: 0

                Repeater {
                    model: ["Agents", "Flocks", "Swarms"]

                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 36
                        color: currentTab === index ? Style.surfaceVariant : "transparent"
                        border.color: currentTab === index ? Style.primary : Style.cardBorder
                        border.width: currentTab === index ? 2 : 1
                        radius: index === 0 ? 6 : (index === 2 ? 6 : 0)

                        // Round only left corners for first, right for last
                        Rectangle {
                            visible: index === 0
                            anchors.right: parent.right
                            width: parent.radius
                            height: parent.height
                            color: parent.color
                        }

                        Rectangle {
                            visible: index === 2
                            anchors.left: parent.left
                            width: parent.radius
                            height: parent.height
                            color: parent.color
                        }

                        Text {
                            anchors.centerIn: parent
                            text: modelData
                            font.pixelSize: 13
                            font.bold: currentTab === index
                            color: currentTab === index ? Style.primary : Style.textSecondary
                        }

                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: currentTab = index
                        }
                    }
                }
            }

            // Select All / Deselect All
            RowLayout {
                Layout.fillWidth: true
                spacing: 8

                Rectangle {
                    Layout.preferredWidth: selAllText.implicitWidth + 20
                    Layout.preferredHeight: 28
                    radius: 4
                    color: selAllMa.containsMouse ? Style.surfaceVariant : Style.fieldBackground
                    border.color: Style.fieldBorder
                    border.width: 1

                    Text {
                        id: selAllText
                        anchors.centerIn: parent
                        text: "Select All"
                        font.pixelSize: 11
                        color: Style.textPrimary
                    }

                    MouseArea {
                        id: selAllMa
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: selectAll(true)
                    }
                }

                Rectangle {
                    Layout.preferredWidth: deselAllText.implicitWidth + 20
                    Layout.preferredHeight: 28
                    radius: 4
                    color: deselAllMa.containsMouse ? Style.surfaceVariant : Style.fieldBackground
                    border.color: Style.fieldBorder
                    border.width: 1

                    Text {
                        id: deselAllText
                        anchors.centerIn: parent
                        text: "Deselect All"
                        font.pixelSize: 11
                        color: Style.textPrimary
                    }

                    MouseArea {
                        id: deselAllMa
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: selectAll(false)
                    }
                }

                Item { Layout.fillWidth: true }

                Text {
                    text: currentSelectionCount() + " selected"
                    font.pixelSize: 12
                    color: Style.textTertiary
                }
            }

            // Content area - tab pages
            Rectangle {
                Layout.fillWidth: true
                Layout.fillHeight: true
                color: Style.fieldBackground
                border.color: Style.fieldBorder
                border.width: 1
                radius: 6

                // Agents tab
                ListView {
                    id: agentsListView
                    anchors.fill: parent
                    anchors.margins: 4
                    visible: currentTab === 0
                    model: viewModel.getAgents()
                    clip: true
                    boundsBehavior: Flickable.StopAtBounds
                    spacing: 2

                    delegate: SelectionRow {
                        width: agentsListView.width
                        itemId: modelData.id || ""
                        itemName: modelData.name || ""
                        itemDetail: (modelData.providerId || "") + " / " + (modelData.model || "")
                        checked: !!agentChecks[modelData.id]
                        onToggled: {
                            var copy = agentChecks
                            copy[modelData.id] = !checked
                            agentChecks = copy
                        }
                    }

                    Text {
                        anchors.centerIn: parent
                        visible: agentsListView.count === 0
                        text: "No agents configured"
                        font.pixelSize: 13
                        color: Style.textHint
                        font.italic: true
                    }
                }

                // Flocks tab
                ListView {
                    id: flocksListView
                    anchors.fill: parent
                    anchors.margins: 4
                    visible: currentTab === 1
                    model: viewModel.getFlocks()
                    clip: true
                    boundsBehavior: Flickable.StopAtBounds
                    spacing: 2

                    delegate: SelectionRow {
                        width: flocksListView.width
                        itemId: modelData.id || ""
                        itemName: modelData.name || ""
                        itemDetail: {
                            var agentIds = modelData.agentIds || []
                            return agentIds.length + " agent" + (agentIds.length !== 1 ? "s" : "")
                        }
                        checked: !!flockChecks[modelData.id]
                        onToggled: {
                            var copy = flockChecks
                            copy[modelData.id] = !checked
                            flockChecks = copy
                        }
                    }

                    Text {
                        anchors.centerIn: parent
                        visible: flocksListView.count === 0
                        text: "No flocks configured"
                        font.pixelSize: 13
                        color: Style.textHint
                        font.italic: true
                    }
                }

                // Swarms tab
                ListView {
                    id: swarmsListView
                    anchors.fill: parent
                    anchors.margins: 4
                    visible: currentTab === 2
                    model: viewModel.getSwarms()
                    clip: true
                    boundsBehavior: Flickable.StopAtBounds
                    spacing: 2

                    delegate: SelectionRow {
                        width: swarmsListView.width
                        itemId: modelData.id || ""
                        itemName: modelData.name || ""
                        itemDetail: {
                            var members = modelData.members || []
                            return members.length + " member" + (members.length !== 1 ? "s" : "")
                        }
                        checked: !!swarmChecks[modelData.id]
                        onToggled: {
                            var copy = swarmChecks
                            copy[modelData.id] = !checked
                            swarmChecks = copy
                        }
                    }

                    Text {
                        anchors.centerIn: parent
                        visible: swarmsListView.count === 0
                        text: "No swarms configured"
                        font.pixelSize: 13
                        color: Style.textHint
                        font.italic: true
                    }
                }
            }

            // OK / Cancel buttons
            RowLayout {
                Layout.fillWidth: true
                spacing: 12

                Item { Layout.fillWidth: true }

                Rectangle {
                    Layout.preferredWidth: cancelText.implicitWidth + 32
                    Layout.preferredHeight: 36
                    radius: 6
                    color: cancelMa.containsMouse ? Style.surfaceVariant : Style.fieldBackground
                    border.color: Style.fieldBorder
                    border.width: 1

                    Text {
                        id: cancelText
                        anchors.centerIn: parent
                        text: "Cancel"
                        font.pixelSize: 13
                        color: Style.textPrimary
                    }

                    MouseArea {
                        id: cancelMa
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: root.cancelled()
                    }
                }

                Rectangle {
                    Layout.preferredWidth: okText.implicitWidth + 32
                    Layout.preferredHeight: 36
                    radius: 6
                    color: okMa.containsMouse ? Style.secondary : Style.primary

                    Text {
                        id: okText
                        anchors.centerIn: parent
                        text: "OK"
                        font.pixelSize: 13
                        font.bold: true
                        color: Style.textPrimary
                    }

                    MouseArea {
                        id: okMa
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: root.accepted(agentChecks, flockChecks, swarmChecks)
                    }
                }
            }
        }
    }

    function selectAll(select) {
        if (currentTab === 0) {
            var agents = viewModel.getAgents()
            var aCopy = agentChecks
            for (var i = 0; i < agents.length; i++)
                aCopy[agents[i].id] = select
            agentChecks = aCopy
        } else if (currentTab === 1) {
            var flocks = viewModel.getFlocks()
            var fCopy = flockChecks
            for (var j = 0; j < flocks.length; j++)
                fCopy[flocks[j].id] = select
            flockChecks = fCopy
        } else {
            var swarms = viewModel.getSwarms()
            var sCopy = swarmChecks
            for (var k = 0; k < swarms.length; k++)
                sCopy[swarms[k].id] = select
            swarmChecks = sCopy
        }
    }

    function currentSelectionCount() {
        var count = 0
        var checks
        if (currentTab === 0) checks = agentChecks
        else if (currentTab === 1) checks = flockChecks
        else checks = swarmChecks
        for (var key in checks) {
            if (checks[key]) count++
        }
        return count
    }

    // Inline component for selection rows
    component SelectionRow: Rectangle {
        id: selRow
        property string itemId
        property string itemName
        property string itemDetail
        property bool checked: false
        signal toggled()

        height: 42
        radius: 4
        color: checked ? Qt.rgba(Style.primary.r, Style.primary.g, Style.primary.b, 0.08) : "transparent"

        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 12
            anchors.rightMargin: 12
            spacing: 10

            CheckBox {
                checked: selRow.checked
                onToggled: selRow.toggled()
                palette.highlight: Style.primary
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: 1

                Text {
                    text: itemName
                    font.pixelSize: 13
                    color: Style.textPrimary
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }

                Text {
                    text: itemDetail
                    font.pixelSize: 11
                    color: Style.textTertiary
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }
            }
        }

        MouseArea {
            anchors.fill: parent
            cursorShape: Qt.PointingHandCursor
            onClicked: selRow.toggled()
        }
    }
}
