//! Claude Code slash commands defined by files on the Rex host.
use crate::{
    files::{DirListing, DirStatus, Files},
    transport::Endpoint,
};
use anyhow::{Context, Result};
use serde_json::{Value, json};
use std::{collections::BTreeMap, time::Duration};

const MAX_SKILL_BYTES: usize = 16 * 1024;
const MAX_SETTINGS_BYTES: usize = 1024 * 1024;
const MAX_DESCRIPTION_CHARS: usize = 300;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum SkillSource {
    BuiltIn,
    User,
    Project,
    Plugin,
}
impl SkillSource {
    pub fn name(self) -> &'static str {
        match self {
            Self::BuiltIn => "built_in",
            Self::User => "user",
            Self::Project => "project",
            Self::Plugin => "plugin",
        }
    }
    fn precedence(self) -> u8 {
        match self {
            Self::Project => 3,
            Self::User => 2,
            Self::Plugin => 1,
            Self::BuiltIn => 0,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SlashCommand {
    pub name: String,
    pub description: String,
    pub source: SkillSource,
    pub plugin: Option<String>,
}
impl SlashCommand {
    pub fn to_json(&self) -> Value {
        json!({"name":self.name,"description":self.description,"source":self.source.name(),"plugin":self.plugin})
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Discovery {
    pub cwd: Option<String>,
    pub home: String,
    pub commands: Vec<SlashCommand>,
    pub unreadable: usize,
}
impl Discovery {
    pub fn to_json(&self) -> Value {
        json!({"cwd":self.cwd,"home":self.home,"unreadable":self.unreadable,"commands":self.commands.iter().map(SlashCommand::to_json).collect::<Vec<_>>()})
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Plugin {
    pub name: String,
    pub install_path: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Layout {
    /// `<dir>/<skill>/SKILL.md`
    Skills,
    /// `<dir>/<command>.md`, plus one level of `<dir>/<namespace>/<command>.md`
    Commands,
    Namespace(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Root {
    pub dir: String,
    pub layout: Layout,
    pub source: SkillSource,
    pub plugin: Option<String>,
}
impl Root {
    fn new(dir: String, layout: Layout, source: SkillSource, plugin: Option<&str>) -> Self {
        Self {
            dir,
            layout,
            source,
            plugin: plugin.map(str::to_owned),
        }
    }
    fn qualify(&self, name: &str) -> String {
        match &self.plugin {
            Some(plugin) => format!("{plugin}:{name}"),
            None => name.to_owned(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    pub path: String,
    pub fallback_name: String,
    pub is_skill: bool,
    pub source: SkillSource,
    pub plugin: Option<String>,
}

/// The working directory and each ancestor below `home` (or below `/` outside it).
pub fn project_dirs(cwd: &str, home: &str) -> Vec<String> {
    let home = home.trim_end_matches('/');
    let mut dirs = Vec::new();
    let mut dir = cwd.trim_end_matches('/');
    while !dir.is_empty() && dir != home {
        dirs.push(dir.to_owned());
        dir = match dir.rfind('/') {
            Some(slash) => &dir[..slash],
            None => break,
        };
    }
    dirs
}

fn contains(ancestor: &str, path: &str) -> bool {
    let ancestor = ancestor.trim_end_matches('/');
    path == ancestor
        || path
            .strip_prefix(ancestor)
            .is_some_and(|rest| rest.starts_with('/'))
}

/// `settings` are ordered from lowest to highest precedence.
pub fn enabled_plugins(installed: &Value, settings: &[Value], cwd: Option<&str>) -> Vec<Plugin> {
    let mut enabled = BTreeMap::new();
    for layer in settings {
        if let Some(map) = layer["enabledPlugins"].as_object() {
            for (key, value) in map {
                enabled.insert(key.as_str(), value.as_bool() == Some(true));
            }
        }
    }
    let Some(plugins) = installed["plugins"].as_object() else {
        return Vec::new();
    };
    plugins
        .iter()
        .filter(|(key, _)| enabled.get(key.as_str()) == Some(&true))
        .filter_map(|(key, installs)| {
            let install = installs.as_array()?.iter().find(|install| {
                install["scope"] == "user"
                    || install["projectPath"]
                        .as_str()
                        .zip(cwd)
                        .is_some_and(|(project, cwd)| contains(project, cwd))
            })?;
            Some(Plugin {
                name: key.split('@').next().unwrap_or(key).to_owned(),
                install_path: install["installPath"]
                    .as_str()?
                    .trim_end_matches('/')
                    .to_owned(),
            })
        })
        .collect()
}

pub fn roots(home: &str, cwd: Option<&str>, plugins: &[Plugin]) -> Vec<Root> {
    let home = home.trim_end_matches('/');
    let mut roots = Vec::new();
    for dir in cwd.map(|cwd| project_dirs(cwd, home)).unwrap_or_default() {
        roots.push(Root::new(
            format!("{dir}/.claude/skills"),
            Layout::Skills,
            SkillSource::Project,
            None,
        ));
        roots.push(Root::new(
            format!("{dir}/.claude/commands"),
            Layout::Commands,
            SkillSource::Project,
            None,
        ));
    }
    roots.push(Root::new(
        format!("{home}/.claude/skills"),
        Layout::Skills,
        SkillSource::User,
        None,
    ));
    roots.push(Root::new(
        format!("{home}/.claude/commands"),
        Layout::Commands,
        SkillSource::User,
        None,
    ));
    for plugin in plugins {
        let name = Some(plugin.name.as_str());
        roots.push(Root::new(
            format!("{}/skills", plugin.install_path),
            Layout::Skills,
            SkillSource::Plugin,
            name,
        ));
        roots.push(Root::new(
            format!("{}/commands", plugin.install_path),
            Layout::Commands,
            SkillSource::Plugin,
            name,
        ));
    }
    roots
}

/// Files to read from one listed root, and the command namespaces still to list.
pub fn expand(root: &Root, listing: &DirListing) -> (Vec<Candidate>, Vec<Root>) {
    let mut candidates = Vec::new();
    let mut namespaces = Vec::new();
    if listing.status != DirStatus::Ok {
        return (candidates, namespaces);
    }
    let candidate = |path: String, name: &str, is_skill: bool| Candidate {
        path,
        fallback_name: root.qualify(name),
        is_skill,
        source: root.source,
        plugin: root.plugin.clone(),
    };
    for entry in listing
        .entries
        .iter()
        .filter(|entry| !entry.name.starts_with('.'))
    {
        let path = format!("{}/{}", root.dir, entry.name);
        let stem = entry
            .name
            .strip_suffix(".md")
            .filter(|stem| entry.file && !stem.is_empty());
        match (&root.layout, entry.file, stem) {
            (Layout::Skills, false, _) => {
                candidates.push(candidate(format!("{path}/SKILL.md"), &entry.name, true))
            }
            (Layout::Commands, false, _) => namespaces.push(Root {
                dir: path,
                layout: Layout::Namespace(entry.name.clone()),
                ..root.clone()
            }),
            (Layout::Commands, true, Some(stem)) => candidates.push(candidate(path, stem, false)),
            (Layout::Namespace(namespace), true, Some(stem)) => {
                candidates.push(candidate(path, &format!("{namespace}:{stem}"), false))
            }
            _ => (),
        }
    }
    (candidates, namespaces)
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct FrontMatter {
    pub name: Option<String>,
    pub description: Option<String>,
    pub user_invocable: bool,
    pub first_line: Option<String>,
}

pub fn front_matter(text: &str) -> FrontMatter {
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    let mut lines = text.split_inclusive('\n');
    let mut fields: Vec<(&str, Vec<&str>)> = Vec::new();
    let mut body = text;
    if lines.next().map(str::trim_end) == Some("---") {
        body = "";
        let mut consumed = text.find('\n').map_or(text.len(), |n| n + 1);
        for raw in lines {
            consumed += raw.len();
            let line = raw.trim_end_matches(['\n', '\r']);
            if matches!(line.trim_end(), "---" | "...") {
                body = &text[consumed..];
                break;
            }
            let top_level = !line.starts_with([' ', '\t']) && !line.trim().is_empty();
            match line
                .split_once(':')
                .filter(|_| top_level && !line.starts_with('#'))
            {
                Some((key, rest)) => fields.push((key.trim(), vec![rest])),
                None => {
                    if let Some((_, value)) = fields.last_mut() {
                        value.push(line);
                    }
                }
            }
        }
    }
    let field = |name: &str| {
        fields
            .iter()
            .find(|(key, _)| *key == name)
            .map(|(_, raw)| scalar(raw))
    };
    FrontMatter {
        name: field("name").filter(|name| !name.is_empty() && !name.contains(char::is_whitespace)),
        description: field("description")
            .map(|d| clean(&d))
            .filter(|d| !d.is_empty()),
        user_invocable: field("user-invocable")
            .is_none_or(|value| !value.eq_ignore_ascii_case("false")),
        first_line: body
            .lines()
            .map(|line| clean(line.trim_start_matches('#')))
            .find(|line| !line.is_empty()),
    }
}

fn scalar(raw: &[&str]) -> String {
    let first = raw[0].trim();
    let joined = || {
        std::iter::once(first)
            .chain(raw[1..].iter().map(|line| line.trim()))
            .collect::<Vec<_>>()
            .join(" ")
    };
    let collapsed = |text: &str| text.split_whitespace().collect::<Vec<_>>().join(" ");
    if first.starts_with(['|', '>']) {
        return collapsed(&raw[1..].join(" "));
    }
    if let Some(rest) = first.strip_prefix('"') {
        let text = std::iter::once(rest)
            .chain(raw[1..].iter().map(|line| line.trim()))
            .collect::<Vec<_>>()
            .join(" ");
        let mut out = String::new();
        let mut chars = text.chars();
        while let Some(c) = chars.next() {
            match c {
                '"' => break,
                '\\' => match chars.next() {
                    Some('n') => out.push('\n'),
                    Some('t') => out.push('\t'),
                    Some(other) => out.push(other),
                    None => (),
                },
                c => out.push(c),
            }
        }
        return collapsed(&out);
    }
    if let Some(rest) = first.strip_prefix('\'') {
        let text = std::iter::once(rest)
            .chain(raw[1..].iter().map(|line| line.trim()))
            .collect::<Vec<_>>()
            .join(" ");
        let mut out = String::new();
        let mut chars = text.chars().peekable();
        while let Some(c) = chars.next() {
            if c == '\'' {
                if chars.peek() != Some(&'\'') {
                    break;
                }
                chars.next();
            }
            out.push(c);
        }
        return collapsed(&out);
    }
    let plain = joined();
    let plain = plain.find(" #").map_or(plain.as_str(), |n| &plain[..n]);
    collapsed(plain)
}

fn clean(text: &str) -> String {
    let text: String = text
        .chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect();
    let text = text.split_whitespace().collect::<Vec<_>>().join(" ");
    if text.chars().count() <= MAX_DESCRIPTION_CHARS {
        return text;
    }
    let mut cut: String = text.chars().take(MAX_DESCRIPTION_CHARS - 1).collect();
    cut.push('…');
    cut
}

pub fn command(candidate: &Candidate, text: &str) -> Option<SlashCommand> {
    let front = front_matter(text);
    if !front.user_invocable {
        return None;
    }
    let name = match (&front.name, candidate.is_skill) {
        (Some(name), true) => match &candidate.plugin {
            Some(plugin) => format!("{plugin}:{name}"),
            None => name.clone(),
        },
        _ => candidate.fallback_name.clone(),
    };
    Some(SlashCommand {
        name,
        description: front.description.or(front.first_line).unwrap_or_default(),
        source: candidate.source,
        plugin: candidate.plugin.clone(),
    })
}

/// One command per name, preferring project over user over plugin, sorted by source then name.
pub fn finalize(commands: Vec<SlashCommand>) -> Vec<SlashCommand> {
    let mut by_name: BTreeMap<String, SlashCommand> = BTreeMap::new();
    for command in commands {
        match by_name.get(&command.name) {
            Some(kept) if kept.source.precedence() >= command.source.precedence() => (),
            _ => {
                by_name.insert(command.name.clone(), command);
            }
        }
    }
    let mut commands: Vec<_> = by_name.into_values().collect();
    commands.sort_by_key(|command| command.source);
    commands
}

fn json_file(content: Option<&Result<Vec<u8>>>) -> Value {
    content
        .and_then(|content| content.as_ref().ok())
        .and_then(|bytes| serde_json::from_slice(bytes).ok())
        .unwrap_or_default()
}

pub async fn discover(
    endpoint: Endpoint,
    session: &str,
    block: &str,
    timeout: Duration,
) -> Result<Discovery> {
    let mut files = Files::connect(endpoint, session, block).await?;
    let result = tokio::time::timeout(timeout, scan(&mut files))
        .await
        .context("Skill discovery timed out")
        .and_then(|result| result);
    files.close().await;
    result
}

async fn scan(files: &mut Files) -> Result<Discovery> {
    let location = files.location().await?;
    let home = location
        .home
        .context("Rex did not report the home directory")?;
    let cwd = location.cwd;
    let mut settings_paths = vec![
        format!("{home}/.claude/plugins/installed_plugins.json"),
        format!("{home}/.claude/settings.json"),
    ];
    if let Some(cwd) = &cwd {
        settings_paths.push(format!("{cwd}/.claude/settings.json"));
        settings_paths.push(format!("{cwd}/.claude/settings.local.json"));
    }
    let settings = files
        .read_files(&settings_paths, MAX_SETTINGS_BYTES)
        .await?;
    let layers: Vec<Value> = (1..settings.len())
        .map(|n| json_file(settings.get(n)))
        .collect();
    let plugins = enabled_plugins(&json_file(settings.first()), &layers, cwd.as_deref());

    let mut candidates = Vec::new();
    let mut pending = roots(&home, cwd.as_deref(), &plugins);
    while !pending.is_empty() {
        let dirs: Vec<String> = pending.iter().map(|root| root.dir.clone()).collect();
        let listings = files.list_dirs(&dirs, true).await?;
        let mut next = Vec::new();
        for (root, listing) in pending.iter().zip(listings) {
            let Ok(listing) = listing else { continue };
            let (found, namespaces) = expand(root, &listing);
            candidates.extend(found);
            next.extend(namespaces);
        }
        pending = next;
    }

    let paths: Vec<String> = candidates
        .iter()
        .map(|candidate| candidate.path.clone())
        .collect();
    let contents = files.read_files(&paths, MAX_SKILL_BYTES).await?;
    let mut unreadable = 0;
    let mut commands = Vec::new();
    for (candidate, content) in candidates.iter().zip(contents) {
        match content {
            Ok(bytes) => commands.extend(command(candidate, &String::from_utf8_lossy(&bytes))),
            Err(_) => unreadable += 1,
        }
    }
    Ok(Discovery {
        cwd,
        home,
        commands: finalize(commands),
        unreadable,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::files::DirEntry;

    fn listing(entries: &[(&str, bool)]) -> DirListing {
        DirListing {
            path: String::new(),
            home: String::new(),
            status: DirStatus::Ok,
            entries: entries
                .iter()
                .map(|(name, file)| DirEntry {
                    name: (*name).into(),
                    file: *file,
                    symlink: false,
                })
                .collect(),
            truncated: false,
        }
    }

    fn slash(name: &str, source: SkillSource) -> SlashCommand {
        SlashCommand {
            name: name.into(),
            description: String::new(),
            source,
            plugin: None,
        }
    }

    #[test]
    fn front_matter_reads_plain_quoted_and_block_values() {
        let plain = front_matter(
            "---\nname: rex\ndescription: Use the rex CLI. # note\nmetadata:\n  name: nested\n---\n# Body\n",
        );
        assert_eq!(plain.name.as_deref(), Some("rex"));
        assert_eq!(plain.description.as_deref(), Some("Use the rex CLI."));
        assert!(plain.user_invocable);
        let quoted = front_matter(
            "---\nname: browse\ndescription: \"Drive a \\\"real\\\" browser: open a page. (gstack)\"\n---\n",
        );
        assert_eq!(
            quoted.description.as_deref(),
            Some("Drive a \"real\" browser: open a page. (gstack)")
        );
        let single = front_matter("---\ndescription: 'It''s here'\n---\n");
        assert_eq!(single.description.as_deref(), Some("It's here"));
        let literal = front_matter(
            "---\nname: eval-plan\ndescription: |\n  Preregister a paid eval,\n  then run a pilot.\ntriggers:\n  - x\n---\n",
        );
        assert_eq!(
            literal.description.as_deref(),
            Some("Preregister a paid eval, then run a pilot.")
        );
        let folded = front_matter(
            "---\ndescription: >-\n  Use when building\n\n  a bot UI.\ndisable-model-invocation: true\n---\n",
        );
        assert_eq!(
            folded.description.as_deref(),
            Some("Use when building a bot UI.")
        );
        let continued = front_matter("---\ndescription: A plain scalar\n  that wraps.\n---\n");
        assert_eq!(
            continued.description.as_deref(),
            Some("A plain scalar that wraps.")
        );
    }

    #[test]
    fn front_matter_falls_back_and_never_fails() {
        let none = front_matter("\n\n# Review the diff\nMore text\n");
        assert_eq!((none.name, none.description), (None, None));
        assert_eq!(none.first_line.as_deref(), Some("Review the diff"));
        let hidden = front_matter("---\nname: helper\nuser-invocable: false\n---\n");
        assert!(!hidden.user_invocable);
        let unterminated = front_matter("---\nname: cut\ndescription: trunc");
        assert_eq!(unterminated.name.as_deref(), Some("cut"));
        assert_eq!(unterminated.first_line, None);
        let control = front_matter(&format!(
            "---\ndescription: \"a\\tb\u{7}c{}\"\nname: two words\n---\n",
            "x".repeat(400)
        ));
        let description = control.description.unwrap();
        assert!(description.starts_with("a b c"));
        assert_eq!(description.chars().count(), MAX_DESCRIPTION_CHARS);
        assert_eq!(control.name, None);
        for text in [
            "",
            "---",
            "---\n:\n---",
            "---\n  : x\n---",
            "\u{feff}---\nname: bom\n---",
        ] {
            let _ = front_matter(text);
        }
        assert_eq!(
            front_matter("\u{feff}---\nname: bom\n---").name.as_deref(),
            Some("bom")
        );
    }

    #[test]
    fn plugins_need_enablement_and_a_matching_scope() {
        let installed = json!({"version":2,"plugins":{
            "superpowers@official":[{"scope":"user","installPath":"/c/superpowers/7/"}],
            "typescript-lsp@official":[{"scope":"user","installPath":"/c/ts"}],
            "sentry@official":[{"scope":"project","projectPath":"/w/atlas","installPath":"/c/sentry"}],
            "local@official":[{"scope":"local","projectPath":"/w/pina","installPath":"/c/local"}],
            "unlisted@official":[{"scope":"user","installPath":"/c/unlisted"}]
        }});
        let user = json!({"enabledPlugins":{"superpowers@official":true,"typescript-lsp@official":false,"sentry@official":true,"local@official":true}});
        let project = json!({"enabledPlugins":{"superpowers@official":false}});
        let names = |plugins: Vec<Plugin>| plugins.into_iter().map(|p| p.name).collect::<Vec<_>>();
        assert_eq!(
            names(enabled_plugins(
                &installed,
                std::slice::from_ref(&user),
                Some("/w/pina/app")
            )),
            ["local", "superpowers"]
        );
        assert_eq!(
            names(enabled_plugins(
                &installed,
                std::slice::from_ref(&user),
                Some("/w/atlas")
            )),
            ["sentry", "superpowers"]
        );
        assert_eq!(
            names(enabled_plugins(
                &installed,
                std::slice::from_ref(&user),
                Some("/w/atlasship")
            )),
            ["superpowers"]
        );
        assert_eq!(
            names(enabled_plugins(
                &installed,
                &[user, project],
                Some("/w/atlas")
            )),
            ["sentry"]
        );
        assert_eq!(
            enabled_plugins(
                &installed,
                &[json!({"enabledPlugins":{"superpowers@official":true}})],
                None
            )[0]
            .install_path,
            "/c/superpowers/7"
        );
        assert!(enabled_plugins(&Value::Null, &[], None).is_empty());
    }

    #[test]
    fn project_dirs_stop_below_home() {
        assert_eq!(
            project_dirs("/Users/j/Documents/pina/", "/Users/j"),
            ["/Users/j/Documents/pina", "/Users/j/Documents"]
        );
        assert!(project_dirs("/Users/j", "/Users/j/").is_empty());
        assert_eq!(project_dirs("/srv/app", "/Users/j"), ["/srv/app", "/srv"]);
    }

    #[test]
    fn roots_expand_into_named_candidates() {
        let plugin = Plugin {
            name: "superpowers".into(),
            install_path: "/c/sp".into(),
        };
        let all = roots("/h", Some("/h/w"), &[plugin]);
        let dirs: Vec<_> = all.iter().map(|root| root.dir.as_str()).collect();
        assert_eq!(
            dirs,
            [
                "/h/w/.claude/skills",
                "/h/w/.claude/commands",
                "/h/.claude/skills",
                "/h/.claude/commands",
                "/c/sp/skills",
                "/c/sp/commands"
            ]
        );

        let (skills, none) = expand(
            &all[4],
            &listing(&[
                ("brainstorming", false),
                ("README.md", true),
                (".hidden", false),
            ]),
        );
        assert!(none.is_empty());
        assert_eq!(skills.len(), 1);
        assert_eq!(
            (skills[0].path.as_str(), skills[0].fallback_name.as_str()),
            (
                "/c/sp/skills/brainstorming/SKILL.md",
                "superpowers:brainstorming"
            )
        );

        let (commands, namespaces) = expand(
            &all[3],
            &listing(&[("review.md", true), ("notes.txt", true), ("git", false)]),
        );
        assert_eq!(commands[0].fallback_name, "review");
        assert_eq!(namespaces[0].dir, "/h/.claude/commands/git");
        let (nested, deeper) = expand(
            &namespaces[0],
            &listing(&[("commit.md", true), ("more", false)]),
        );
        assert_eq!(
            (nested[0].fallback_name.as_str(), deeper.len()),
            ("git:commit", 0)
        );

        let mut missing = listing(&[("x", false)]);
        missing.status = DirStatus::Missing;
        assert_eq!(expand(&all[0], &missing), (vec![], vec![]));
    }

    #[test]
    fn commands_take_names_from_skills_front_matter_only() {
        let skill = Candidate {
            path: "p".into(),
            fallback_name: "figma:dir".into(),
            is_skill: true,
            source: SkillSource::Plugin,
            plugin: Some("figma".into()),
        };
        let named = command(
            &skill,
            "---\nname: figma-use\ndescription: Use Figma.\n---\n",
        )
        .unwrap();
        assert_eq!(
            (named.name.as_str(), named.description.as_str()),
            ("figma:figma-use", "Use Figma.")
        );
        assert_eq!(
            command(&skill, "no front matter").unwrap().name,
            "figma:dir"
        );
        assert_eq!(command(&skill, "---\nuser-invocable: false\n---\n"), None);
        let file = Candidate {
            path: "p".into(),
            fallback_name: "git:commit".into(),
            is_skill: false,
            source: SkillSource::User,
            plugin: None,
        };
        let cmd = command(&file, "---\nname: other\n---\nCommit staged changes\n").unwrap();
        assert_eq!(
            (cmd.name.as_str(), cmd.description.as_str()),
            ("git:commit", "Commit staged changes")
        );
    }

    #[test]
    fn finalize_prefers_project_then_user_then_plugin() {
        let commands = finalize(vec![
            slash("review", SkillSource::Plugin),
            slash("review", SkillSource::User),
            slash("deploy", SkillSource::User),
            slash("deploy", SkillSource::Project),
            slash("zeta", SkillSource::User),
            slash("figma:use", SkillSource::Plugin),
            slash("alpha", SkillSource::User),
        ]);
        let shown: Vec<_> = commands
            .iter()
            .map(|c| (c.name.as_str(), c.source))
            .collect();
        assert_eq!(
            shown,
            [
                ("alpha", SkillSource::User),
                ("review", SkillSource::User),
                ("zeta", SkillSource::User),
                ("deploy", SkillSource::Project),
                ("figma:use", SkillSource::Plugin),
            ]
        );
    }
}
