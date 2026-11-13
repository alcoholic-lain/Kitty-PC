using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Windows.Forms;

internal static class KpsInstaller
{
    [STAThread]
    private static void Main()
    {
        Application.EnableVisualStyles();

        try
        {
            string serverDirectory = SelectServerDirectory();
            if (serverDirectory == null)
            {
                return;
            }

            Install(serverDirectory);
            bool profileCanLoad = EnsureExecutionPolicy();
            string message =
                "kps is installed for your Windows PowerShell and PowerShell 7 profiles.\r\n" +
                "Close and reopen PowerShell, then run: kps -s";
            if (!profileCanLoad)
            {
                message += "\r\n\r\nPowerShell may block the profile script. To allow locally installed scripts for your account, run:\r\n" +
                    "Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned\r\n" +
                    "A managed policy may require help from your administrator.";
            }

            MessageBox.Show(
                message,
                "Kitty-PC kps installer",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
        }
        catch (Exception exception)
        {
            MessageBox.Show(
                exception.Message,
                "Kitty-PC kps installer",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
        }
    }

    private static bool EnsureExecutionPolicy()
    {
        string policy;
        try
        {
            policy = RunPowerShell("(Get-ExecutionPolicy).ToString()");
        }
        catch
        {
            return false;
        }

        if (policy.Equals("RemoteSigned", StringComparison.OrdinalIgnoreCase) ||
            policy.Equals("Unrestricted", StringComparison.OrdinalIgnoreCase) ||
            policy.Equals("Bypass", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        DialogResult result = MessageBox.Show(
            "PowerShell's effective execution policy is " + policy + ". This blocks the profile script that defines kps.\r\n\r\n" +
            "Set the policy to RemoteSigned for your Windows account only? This does not change the machine-wide policy.",
            "PowerShell execution policy",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning);
        if (result != DialogResult.Yes)
        {
            return false;
        }

        try
        {
            RunPowerShell(
                "Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned -Force; " +
                "if ((Get-ExecutionPolicy).ToString() -ne 'RemoteSigned') { exit 1 }");
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static string RunPowerShell(string command)
    {
        string powerShellPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.System),
            @"WindowsPowerShell\v1.0\powershell.exe");
        ProcessStartInfo startInfo = new ProcessStartInfo
        {
            FileName = powerShellPath,
            Arguments = "-NoLogo -NoProfile -NonInteractive -Command \"" + command + "\"",
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };

        using (Process process = Process.Start(startInfo))
        {
            if (process == null)
            {
                throw new InvalidOperationException("Could not start Windows PowerShell.");
            }

            string output = process.StandardOutput.ReadToEnd();
            process.StandardError.ReadToEnd();
            process.WaitForExit();
            if (process.ExitCode != 0)
            {
                throw new InvalidOperationException("Windows PowerShell returned exit code " + process.ExitCode + ".");
            }

            return output.Trim();
        }
    }

    private static string SelectServerDirectory()
    {
        string initialDirectory = null;
        string startupDirectory = Application.StartupPath;
        string parentDirectory = Directory.GetParent(startupDirectory) == null
            ? startupDirectory
            : Directory.GetParent(startupDirectory).FullName;
        string[] candidates =
        {
            startupDirectory,
            parentDirectory,
            Path.Combine(parentDirectory, "server")
        };

        foreach (string candidate in candidates)
        {
            if (File.Exists(Path.Combine(candidate, "server.py")))
            {
                initialDirectory = candidate;
                break;
            }
        }

        using (FolderBrowserDialog dialog = new FolderBrowserDialog())
        {
            dialog.Description = "Select the Kitty-PC server folder containing server.py.";
            dialog.ShowNewFolderButton = false;
            if (initialDirectory != null)
            {
                dialog.SelectedPath = initialDirectory;
            }

            if (dialog.ShowDialog() != DialogResult.OK)
            {
                return null;
            }

            string selectedDirectory = Path.GetFullPath(dialog.SelectedPath);
            if (!File.Exists(Path.Combine(selectedDirectory, "server.py")))
            {
                throw new InvalidOperationException(
                    "The selected folder does not contain server.py.");
            }

            return selectedDirectory;
        }
    }

    private static void Install(string serverDirectory)
    {
        string localAppData = Environment.GetFolderPath(
            Environment.SpecialFolder.LocalApplicationData);
        string installDirectory = Path.Combine(localAppData, "Kitty-PC");
        Directory.CreateDirectory(installDirectory);

        string profileScript = Path.Combine(installDirectory, "kps.ps1");
        string scriptTemplate;
        using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(
            "KittyPC.KpsProfile.ps1"))
        {
            if (stream == null)
            {
                throw new InvalidOperationException("The embedded kps profile script is missing.");
            }

            using (StreamReader reader = new StreamReader(stream))
            {
                scriptTemplate = reader.ReadToEnd();
            }
        }

        string escapedServerDirectory = serverDirectory.Replace("'", "''");
        string scriptContents = scriptTemplate.Replace(
            "__KPS_SERVER_DIRECTORY__",
            escapedServerDirectory);
        File.WriteAllText(profileScript, scriptContents, new UTF8Encoding(true));

        string escapedProfileScript = profileScript.Replace("'", "''");
        string profileLine = ". '" + escapedProfileScript + "'";
        string documentsDirectory = Environment.GetFolderPath(
            Environment.SpecialFolder.MyDocuments);
        foreach (string profilePath in GetProfilePaths(documentsDirectory))
        {
            EnsureProfileContainsLine(profilePath, profileLine);
        }
    }

    private static string[] GetProfilePaths(string documentsDirectory)
    {
        string[] profileDirectories =
        {
            Path.Combine(documentsDirectory, "WindowsPowerShell"),
            Path.Combine(documentsDirectory, "PowerShell")
        };
        string[] profileFileNames =
        {
            "profile.ps1",
            "Microsoft.PowerShell_profile.ps1"
        };
        string[] profilePaths = new string[profileDirectories.Length * profileFileNames.Length];
        int pathIndex = 0;

        foreach (string profileDirectory in profileDirectories)
        {
            foreach (string profileFileName in profileFileNames)
            {
                profilePaths[pathIndex++] = Path.Combine(profileDirectory, profileFileName);
            }
        }

        return profilePaths;
    }

    private static void EnsureProfileContainsLine(string profilePath, string profileLine)
    {
        string profileDirectory = Path.GetDirectoryName(profilePath);
        if (!String.IsNullOrEmpty(profileDirectory))
        {
            Directory.CreateDirectory(profileDirectory);
        }

        string existingContents = File.Exists(profilePath)
            ? File.ReadAllText(profilePath)
            : String.Empty;

        if (existingContents.IndexOf(profileLine, StringComparison.OrdinalIgnoreCase) >= 0)
        {
            return;
        }

        string separator = existingContents.Length > 0 &&
            !existingContents.EndsWith("\r\n", StringComparison.Ordinal) &&
            !existingContents.EndsWith("\n", StringComparison.Ordinal)
            ? Environment.NewLine
            : String.Empty;
        File.AppendAllText(
            profilePath,
            separator + profileLine + Environment.NewLine,
            new UTF8Encoding(false));
    }
}
