#pragma once
#include <QString>
#include <QList>

enum class SidebarSection {
    Hub,
    NewReport,
    ReportHistory,
    PromptHistory,
    Chat,
    ChatHistory,
    DualChat,
    ModelSearch,
    Statistics,
    Settings,
    Setup,
    Housekeeping,
    Traces,
    Developer,
    Help
};

enum class SidebarGroup {
    Main,
    Reports,
    Chat,
    Tools,
    Admin
};

struct SidebarItem {
    SidebarSection section;
    QString label;
    QString icon; // icon name for QML
    SidebarGroup group;
};

inline QList<SidebarItem> allSidebarItems() {
    return {
        {SidebarSection::Hub,            "Hub",             "home",                SidebarGroup::Main},
        {SidebarSection::NewReport,      "New Report",      "document-new",        SidebarGroup::Reports},
        {SidebarSection::ReportHistory,  "Report History",  "document-open",       SidebarGroup::Reports},
        {SidebarSection::PromptHistory,  "Prompt History",  "history",             SidebarGroup::Reports},
        {SidebarSection::Chat,           "Chat",            "dialog-messages",     SidebarGroup::Chat},
        {SidebarSection::ChatHistory,    "Chat History",    "appointment-new",     SidebarGroup::Chat},
        {SidebarSection::DualChat,       "Dual Chat",       "dialog-messages",     SidebarGroup::Chat},
        {SidebarSection::ModelSearch,    "Model Search",    "system-search",       SidebarGroup::Tools},
        {SidebarSection::Statistics,     "AI Usage",        "office-chart-bar",    SidebarGroup::Tools},
        {SidebarSection::Settings,       "Settings",        "configure",           SidebarGroup::Admin},
        {SidebarSection::Setup,          "AI Setup",        "preferences-system",  SidebarGroup::Admin},
        {SidebarSection::Housekeeping,   "Housekeeping",    "edit-clear",          SidebarGroup::Admin},
        {SidebarSection::Traces,         "API Traces",      "utilities-log-viewer",SidebarGroup::Admin},
        {SidebarSection::Developer,      "Developer",       "applications-development", SidebarGroup::Admin},
        {SidebarSection::Help,           "Help",            "help-about",          SidebarGroup::Admin},
    };
}

inline QString sidebarSectionToString(SidebarSection s) {
    for (const auto &item : allSidebarItems()) {
        if (item.section == s) return item.label;
    }
    return "Hub";
}

inline SidebarSection sidebarSectionFromString(const QString &s) {
    for (const auto &item : allSidebarItems()) {
        if (item.label.compare(s, Qt::CaseInsensitive) == 0) return item.section;
    }
    return SidebarSection::Hub;
}

inline QString sidebarGroupToString(SidebarGroup g) {
    switch (g) {
    case SidebarGroup::Main:    return "Main";
    case SidebarGroup::Reports: return "Reports";
    case SidebarGroup::Chat:    return "Chat";
    case SidebarGroup::Tools:   return "Tools";
    case SidebarGroup::Admin:   return "Admin";
    }
    return "Main";
}
