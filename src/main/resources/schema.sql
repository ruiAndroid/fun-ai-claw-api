create table if not exists claw_instance (
    id uuid primary key,
    name varchar(128) not null,
    host_id uuid not null,
    image varchar(255) not null,
    gateway_host_port integer null,
    runtime varchar(32) not null,
    status varchar(32) not null,
    desired_state varchar(32) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

alter table claw_instance
    add column if not exists gateway_host_port integer null;

create index if not exists idx_claw_instance_created_at on claw_instance (created_at);
create index if not exists idx_claw_instance_host_id on claw_instance (host_id);
create unique index if not exists uq_claw_instance_host_port
    on claw_instance (host_id, gateway_host_port)
    where gateway_host_port is not null;

create table if not exists instance_action (
    id uuid primary key,
    instance_id uuid not null references claw_instance (id) on delete cascade,
    action varchar(32) not null,
    reason text null,
    accepted_at timestamptz not null
);

create index if not exists idx_instance_action_instance_id on instance_action (instance_id);
create index if not exists idx_instance_action_accepted_at on instance_action (accepted_at);

alter table if exists instance_agent_guidance rename to instance_main_prompt;
alter index if exists idx_instance_agent_guidance_updated_at rename to idx_instance_main_prompt_updated_at;

create table if not exists instance_main_prompt (
    instance_id uuid primary key references claw_instance (id) on delete cascade,
    agents_md text not null,
    enabled boolean not null default true,
    updated_by varchar(128) null,
    updated_at timestamptz not null
);

alter table instance_main_prompt
    add column if not exists agents_md text;

alter table instance_main_prompt
    add column if not exists enabled boolean not null default true;

update instance_main_prompt
set enabled = true
where enabled is null;

alter table instance_main_prompt
    alter column enabled set not null;

alter table instance_main_prompt
    add column if not exists updated_by varchar(128) null;

alter table instance_main_prompt
    add column if not exists updated_at timestamptz;

update instance_main_prompt
set agents_md = ''
where agents_md is null;

alter table instance_main_prompt
    alter column agents_md set not null;

update instance_main_prompt
set updated_at = now()
where updated_at is null;

alter table instance_main_prompt
    alter column updated_at set not null;

create index if not exists idx_instance_main_prompt_updated_at on instance_main_prompt (updated_at);

create table if not exists instance_runtime_config (
    instance_id uuid primary key references claw_instance (id) on delete cascade,
    config_toml text not null,
    updated_by varchar(128) null,
    updated_at timestamptz not null
);

alter table instance_runtime_config
    add column if not exists config_toml text;

alter table instance_runtime_config
    add column if not exists updated_by varchar(128) null;

alter table instance_runtime_config
    add column if not exists updated_at timestamptz;

update instance_runtime_config
set config_toml = ''
where config_toml is null;

alter table instance_runtime_config
    alter column config_toml set not null;

update instance_runtime_config
set updated_at = now()
where updated_at is null;

alter table instance_runtime_config
    alter column updated_at set not null;

create index if not exists idx_instance_runtime_config_updated_at on instance_runtime_config (updated_at);

create table if not exists open_client_app (
    app_id varchar(64) primary key,
    name varchar(128) not null,
    app_secret varchar(255) not null,
    enabled boolean not null default true,
    default_instance_id uuid null references claw_instance (id) on delete set null,
    default_agent_id varchar(128) null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

alter table open_client_app
    add column if not exists name varchar(128);

alter table open_client_app
    add column if not exists app_secret varchar(255);

alter table open_client_app
    drop column if exists app_secret_hash;

alter table open_client_app
    add column if not exists enabled boolean not null default true;

alter table open_client_app
    add column if not exists default_instance_id uuid null references claw_instance (id) on delete set null;

alter table open_client_app
    add column if not exists default_agent_id varchar(128) null;

alter table open_client_app
    add column if not exists created_at timestamptz;

alter table open_client_app
    add column if not exists updated_at timestamptz;

update open_client_app
set name = app_id
where name is null;

alter table open_client_app
    alter column name set not null;

update open_client_app
set app_secret = ''
where app_secret is null;

alter table open_client_app
    alter column app_secret set not null;

update open_client_app
set created_at = now()
where created_at is null;

alter table open_client_app
    alter column created_at set not null;

update open_client_app
set updated_at = now()
where updated_at is null;

alter table open_client_app
    alter column updated_at set not null;

create index if not exists idx_open_client_app_updated_at on open_client_app (updated_at);

create table if not exists open_session (
    id uuid primary key,
    app_id varchar(64) not null references open_client_app (app_id) on delete cascade,
    instance_id uuid not null references claw_instance (id) on delete cascade,
    agent_id varchar(128) null,
    external_user_id varchar(128) null,
    external_session_key varchar(128) null,
    title varchar(255) null,
    status varchar(32) not null,
    metadata_json text null,
    ws_token_hash varchar(128) not null,
    ws_token_expires_at timestamptz null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    last_message_at timestamptz null
);

alter table open_session
    add column if not exists app_id varchar(64);

alter table open_session
    add column if not exists instance_id uuid;

alter table open_session
    add column if not exists agent_id varchar(128) null;

alter table open_session
    add column if not exists external_user_id varchar(128) null;

alter table open_session
    add column if not exists external_session_key varchar(128) null;

alter table open_session
    add column if not exists title varchar(255) null;

alter table open_session
    add column if not exists status varchar(32);

alter table open_session
    add column if not exists metadata_json text null;

alter table open_session
    add column if not exists ws_token_hash varchar(128);

alter table open_session
    add column if not exists ws_token_expires_at timestamptz null;

alter table open_session
    add column if not exists created_at timestamptz;

alter table open_session
    add column if not exists updated_at timestamptz;

alter table open_session
    add column if not exists last_message_at timestamptz null;

update open_session
set status = 'ACTIVE'
where status is null;

alter table open_session
    alter column status set not null;

update open_session
set ws_token_hash = repeat('0', 64)
where ws_token_hash is null;

alter table open_session
    alter column ws_token_hash set not null;

update open_session
set created_at = now()
where created_at is null;

alter table open_session
    alter column created_at set not null;

update open_session
set updated_at = now()
where updated_at is null;

alter table open_session
    alter column updated_at set not null;

create index if not exists idx_open_session_app_id on open_session (app_id);
create index if not exists idx_open_session_instance_id on open_session (instance_id);
create index if not exists idx_open_session_updated_at on open_session (updated_at desc);
create unique index if not exists uq_open_session_app_external_session_key
    on open_session (app_id, external_session_key)
    where external_session_key is not null;

create table if not exists open_session_message (
    id uuid primary key,
    session_id uuid not null references open_session (id) on delete cascade,
    event_type varchar(32) not null,
    role varchar(32) not null,
    content text not null,
    thinking_content text null,
    interaction_json text null,
    raw_payload text null,
    provider_message_id varchar(128) null,
    provider_sequence bigint null,
    pending boolean not null default false,
    emitted_at timestamptz null,
    created_at timestamptz not null
);

alter table open_session_message
    add column if not exists session_id uuid;

alter table open_session_message
    add column if not exists event_type varchar(32);

alter table open_session_message
    add column if not exists role varchar(32);

alter table open_session_message
    add column if not exists content text;

alter table open_session_message
    add column if not exists thinking_content text null;

alter table open_session_message
    add column if not exists interaction_json text null;

alter table open_session_message
    add column if not exists raw_payload text null;

alter table open_session_message
    add column if not exists provider_message_id varchar(128) null;

alter table open_session_message
    add column if not exists provider_sequence bigint null;

alter table open_session_message
    add column if not exists pending boolean not null default false;

alter table open_session_message
    add column if not exists emitted_at timestamptz null;

alter table open_session_message
    add column if not exists created_at timestamptz;

update open_session_message
set event_type = 'message'
where event_type is null;

alter table open_session_message
    alter column event_type set not null;

update open_session_message
set role = 'assistant'
where role is null;

alter table open_session_message
    alter column role set not null;

update open_session_message
set content = ''
where content is null;

alter table open_session_message
    alter column content set not null;

update open_session_message
set created_at = now()
where created_at is null;

alter table open_session_message
    alter column created_at set not null;

create index if not exists idx_open_session_message_session_id on open_session_message (session_id, created_at asc);
