using System.Windows;
using System.Windows.Controls;
using AI.Data;
using AI.ViewModels;

namespace AI.Views.Settings;

public partial class SwarmsView : UserControl
{
    private AppViewModel Vm => App.ViewModel;
    private Swarm? _editing;
    private List<SwarmMember> _members = new();

    public SwarmsView()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        MemberProviderCombo.ItemsSource = AppService.Entries.Select(s => s.DisplayName).ToList();
        if (AppService.Entries.Count > 0) MemberProviderCombo.SelectedIndex = 0;
        RefreshList();
    }

    private void RefreshList() => SwarmList.ItemsSource = Vm.AiSettings.Swarms.ToList();

    private void SwarmList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        _editing = SwarmList.SelectedItem as Swarm;
        if (_editing == null) return;
        FormTitle.Text = $"Edit: {_editing.Name}";
        NameBox.Text = _editing.Name;
        _members = _editing.Members.ToList();
        MemberList.ItemsSource = _members.ToList();
    }

    private void MemberProviderCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        var idx = MemberProviderCombo.SelectedIndex;
        if (idx < 0 || idx >= AppService.Entries.Count) return;
        var svc = AppService.Entries[idx];
        var models = Vm.AiSettings.GetModels(svc);
        MemberModelCombo.ItemsSource = models.Count > 0 ? models : new List<string> { svc.DefaultModel };
        MemberModelCombo.Text = svc.DefaultModel;
    }

    private void AddMemberBtn_Click(object sender, RoutedEventArgs e)
    {
        var idx = MemberProviderCombo.SelectedIndex;
        if (idx < 0 || idx >= AppService.Entries.Count) return;
        var svc = AppService.Entries[idx];
        var model = MemberModelCombo.Text.Trim();
        if (string.IsNullOrEmpty(model)) return;
        _members.Add(new SwarmMember { ProviderId = svc.Id, Model = model });
        MemberList.ItemsSource = _members.ToList();
    }

    private void RemoveMemberBtn_Click(object sender, RoutedEventArgs e)
    {
        if (MemberList.SelectedItem is SwarmMember m)
        {
            _members.Remove(m);
            MemberList.ItemsSource = _members.ToList();
        }
    }

    private void NewBtn_Click(object sender, RoutedEventArgs e)
    {
        _editing = new Swarm { Name = "New Swarm" };
        Vm.AiSettings.Swarms.Add(_editing);
        _members = new();
        RefreshList();
        SwarmList.SelectedItem = _editing;
    }

    private void SaveBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_editing == null) return;
        _editing.Name = NameBox.Text.Trim();
        _editing.Members = _members.ToList();
        Vm.UpdateSettings(Vm.AiSettings);
        RefreshList();
        FormTitle.Text = $"Edit: {_editing.Name}";
    }

    private void DeleteBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_editing == null) return;
        if (MessageBox.Show($"Delete swarm '{_editing.Name}'?", "Confirm",
            MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        Vm.AiSettings.Swarms.RemoveAll(s => s.Id == _editing.Id);
        Vm.UpdateSettings(Vm.AiSettings);
        _editing = null;
        _members = new();
        RefreshList();
        FormTitle.Text = "Select a swarm";
    }
}
