# Piggy's shell setup (installed as /etc/profile.d/buddy.sh by the app; changes are overwritten on update)
# Alpine's /etc/profile resets PATH: keep Claude Code (~/.local/bin) and Piggy's tools reachable.
export PATH="$HOME/.local/bin:$HOME/bin:$PATH"
export EDITOR="${EDITOR:-vi}"
alias ls='ls --color=auto'
alias grep='grep --color=auto'
# a compact, colourful prompt: buddy ~/folder ❯
if [ -n "$BASH_VERSION" ]; then
  PS1='\[\e[1;38;5;209m\]buddy\[\e[0m\] \[\e[38;5;110m\]\w\[\e[0m\] \[\e[38;5;245m\]❯\[\e[0m\] '
else
  PS1='buddy $PWD > '
fi
