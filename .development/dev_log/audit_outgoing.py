import collections, re, subprocess, sys

base, target = sys.argv[1:]
def git(*args):
    return subprocess.check_output(['git', *args])

r = f'{base}..{target}'
commits = git('rev-list',r).decode().splitlines()
patterns = {
    'credential-signature': rb'(?i)(?:-----BEGIN [A-Z ]*PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{25,}|(?:AKIA|ASIA)[A-Z0-9]{16}|AIza[A-Za-z0-9_-]{32,}|xox[baprs]-[A-Za-z0-9-]{16,}|sk-(?:proj-)?[A-Za-z0-9_-]{24,}|ctx7sk[-_][A-Za-z0-9_-]{18,}|eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}|https?://[^\s/<>:@]+:[^\s/<>@]+@)',
    'credential-assignment-candidate': rb'''(?i)(?:api[_.-]?key|secret|access[_.-]?token|password|credentials)\s*[:=]\s*["'][^"'\s]{12,}["']''',
    'private-machine-location': rb'(?i)(?:[A-Z]:[\\/]+(?:Users[\\/]+[^\\/\s`"%]+|REPO[\\/]+github|DOC_APR[\\/]+home)|[\\/](?:\.dsh|\.ssh|\.aws)[\\/])',
    'embedded-email': rb'(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}',
    'nonpublic-host': rb'(?i)https?://(?:[^/\s.]+\.)*(?:internal|local|lan|corp)(?:[:/\s]|$)',
}
criteria = {k: re.compile(v) for k,v in patterns.items()}
findings=collections.defaultdict(set)
blobs=0
for line in git('rev-list','--objects',r).splitlines():
    sha, _, name=line.partition(b' ')
    if not name:
        continue
    kind=git('cat-file','-t',sha.decode()).strip()
    if kind != b'blob':
        continue
    path=name.decode('utf-8','replace')
    data=git('cat-file','blob',sha.decode())
    blobs+=1
    if re.search(r'(?i)(?:^|/)(?:\.env(?:\..*)?|\.ssh|\.aws|__pycache__|credentials[^/]*|.*\.(?:hprof|jfr|pprof|otlp|pem|key|pfx|log|pyc|patch|zip|gz))$',path):
        findings['artifact-path'].add(path)
    if b'\x00' in data[:8192]:
        findings['binary-path'].add(path)
    for label, rx in criteria.items():
        if rx.search(data):
            findings[label].add(path)
commit_email_classes=collections.Counter()
for sha in commits:
    metadata=git('show','-s','--format=%ae%n%ce%n%B',sha).splitlines()
    for v in metadata[:2]:
        label = 'github-noreply' if v.lower().endswith(b'@users.noreply.github.com') else 'non-github-noreply'
        commit_email_classes[label]+=1
    message=b'\n'.join(metadata[2:])
    for label, rx in criteria.items():
        if rx.search(message):
            findings[label].add(f'commit-message:{sha[:8]}')
print(f'Outgoing commits={len(commits)}; new blobs={blobs}; attribution labels={dict(commit_email_classes)}')
for label, values in sorted(findings.items()):
    print(label, len(values), sorted(values))
url=git('remote','get-url','--push','origin').decode().strip()
print('origin points to public upstream:',bool(re.fullmatch(r'(?:https://github\.com/|ssh://git@github\.com/|git@github\.com:)btraceio/jafar(?:\.git)?/?',url,re.I)))
