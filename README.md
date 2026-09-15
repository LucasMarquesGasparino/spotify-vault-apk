# Spotify Vault

Explore sua biblioteca do Spotify no Android: sincroniza playlists, álbuns e
histórico de reprodução via **API oficial do Spotify**, guarda tudo em SQLite
local e oferece busca instantânea, estatísticas e modo offline.

- Pacote: `com.spotifyvault.app`
- Auth: OAuth PKCE (`accounts.spotify.com/authorize` → `api/token`),
  redirect `spotifyvault://callback`. Token e refresh salvos no aparelho.
- Alto risco mitigado: busca/ordenação no **SQL** em vez de carregar 2000
  linhas no JS (`PlaysQuery`, com teste unitário em `tests/`).

## Funcionalidades

- Login Spotify com escopos de biblioteca e histórico.
- Sync em 2º plano (`SyncService` + alarmes + boot): playlists, faixas,
  execuções (`plays`), pulos.
- Busca com `LIKE … ESCAPE` (`PlaysQuery`: limite máx. 100, padrão 20).
- Estatísticas: top faixas/artistas, skips, última sync (`VaultBridge`).
- WebView local (`assets/`) + exportação (`exported_at`).
- Log de crash em `vault_crash.log` / `Documents/vault_crash.log`.

## Permissões

`INTERNET`, `ACCESS_NETWORK_STATE`, foreground data-sync, alarmes exatos,
notificações, boot. Sem localização.

## Compilar

```sh
cd ~/projects/spotify-vault-apk
bash build.sh
```

APK em `build/` (+ cópia em `Documents/`). Keystore ignorada pelo git.

## Estrutura

```
spotify-vault-apk/
├── src/com/spotifyvault/app/
│   ├── MainActivity.java / VaultBridge.java
│   ├── SyncService.java / AlarmReceiver.java / BootReceiver.java
│   ├── DatabaseHelper.java / PlaysQuery.java
├── assets/ / res/
└── tests/PlaysQueryTest.java
```

> Necessita de um Client ID do Spotify (dashboard do desenvolvedor) para
> logar; a leitura local funciona com o banco já sincronizado.
