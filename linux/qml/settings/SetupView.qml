import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    color: Style.background

    property var providers: []
    property string selectedProviderId: ""
    property bool showServiceSettings: false

    Component.onCompleted: refreshProviders()

    Connections {
        target: viewModel
        function onSettingsChanged() { refreshProviders() }
        function onModelsLoaded(providerId, models) { refreshProviders() }
    }

    function refreshProviders() {
        providers = viewModel.getProviders()
    }

    function countByState(state) {
        var count = 0
        for (var i = 0; i < providers.length; i++) {
            if (providers[i].state === state) count++
        }
        return count
    }

    // Service settings overlay
    Loader {
        id: serviceLoader
        anchors.fill: parent
        z: 10
        active: showServiceSettings
        source: "ServiceSettingsView.qml"
        onLoaded: {
            item.providerId = selectedProviderId
            item.onBackClicked = function() {
                showServiceSettings = false
                refreshProviders()
            }
        }
    }

    // Main content
    Item {
        anchors.fill: parent
        visible: !showServiceSettings

        ScrollView {
            anchors.fill: parent
            anchors.margins: 20
            contentWidth: availableWidth

            ColumnLayout {
                width: parent.width
                spacing: 16

                // Title row
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 12

                    Text {
                        text: "AI Setup"
                        font.pixelSize: 28
                        font.bold: true
                        color: Style.primary
                        Layout.fillWidth: true
                    }

                    Button {
                        text: "Refresh All Models"
                        onClicked: {
                            for (var i = 0; i < providers.length; i++) {
                                var p = providers[i]
                                if (p.state === "ok") {
                                    viewModel.fetchModels(p.id)
                                }
                            }
                        }
                        background: Rectangle {
                            color: parent.hovered ? Style.surfaceVariant : Style.fieldBackground
                            border.color: Style.primary
                            border.width: 1
                            radius: 6
                        }
                        contentItem: Text {
                            text: parent.text
                            color: Style.primary
                            font.pixelSize: 13
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }

                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: parent.clicked()
                        }
                    }
                }

                // Provider state summary
                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: summaryRow.implicitHeight + 24
                    color: Style.cardBackground
                    border.color: Style.cardBorder
                    border.width: 1
                    radius: 8

                    RowLayout {
                        id: summaryRow
                        anchors.fill: parent
                        anchors.margins: 12
                        spacing: 24

                        StatBadge { label: "Total"; value: providers.length; badgeColor: Style.textSecondary }
                        StatBadge { label: "Active"; value: countByState("ok"); badgeColor: Style.stateOk }
                        StatBadge { label: "Error"; value: countByState("error"); badgeColor: Style.stateError }
                        StatBadge { label: "Inactive"; value: countByState("inactive"); badgeColor: Style.stateInactive }
                        StatBadge { label: "Not Used"; value: countByState("not-used"); badgeColor: Style.stateNotUsed }

                        Item { Layout.fillWidth: true }
                    }
                }

                // Provider grid
                GridLayout {
                    Layout.fillWidth: true
                    columns: Math.max(1, Math.floor((parent.width) / 280))
                    columnSpacing: 12
                    rowSpacing: 12

                    Repeater {
                        model: providers.length

                        Rectangle {
                            required property int index

                            Layout.fillWidth: true
                            Layout.preferredHeight: 80
                            color: cardMouse.containsMouse ? Qt.lighter(Style.cardBackground, 1.15) : Style.cardBackground
                            border.color: cardMouse.containsMouse ? Style.primary : Style.cardBorder
                            border.width: 1
                            radius: 8

                            property var provider: providers[index]

                            RowLayout {
                                anchors.fill: parent
                                anchors.margins: 12
                                spacing: 12

                                // State indicator
                                Rectangle {
                                    width: 10
                                    height: 10
                                    radius: 5
                                    color: Style.colorForState(provider.state || "not-used")
                                }

                                ColumnLayout {
                                    Layout.fillWidth: true
                                    spacing: 2

                                    Text {
                                        text: provider.displayName || ""
                                        font.pixelSize: 14
                                        font.bold: true
                                        color: Style.textPrimary
                                        elide: Text.ElideRight
                                        Layout.fillWidth: true
                                    }

                                    Text {
                                        text: provider.model || "No model set"
                                        font.pixelSize: 12
                                        color: provider.model ? Style.textSecondary : Style.textHint
                                        elide: Text.ElideRight
                                        Layout.fillWidth: true
                                    }

                                    Text {
                                        text: provider.apiFormat || ""
                                        font.pixelSize: 10
                                        color: Style.textTertiary
                                    }
                                }
                            }

                            MouseArea {
                                id: cardMouse
                                anchors.fill: parent
                                cursorShape: Qt.PointingHandCursor
                                hoverEnabled: true
                                onClicked: {
                                    selectedProviderId = provider.id
                                    showServiceSettings = true
                                }
                            }
                        }
                    }
                }

                Item { Layout.fillHeight: true }
            }
        }
    }

    component StatBadge: RowLayout {
        property string label
        property int value
        property color badgeColor: Style.textSecondary

        spacing: 6

        Rectangle {
            width: 8
            height: 8
            radius: 4
            color: badgeColor
        }

        Text {
            text: label + ": " + value
            font.pixelSize: 13
            color: Style.textPrimary
        }
    }
}
