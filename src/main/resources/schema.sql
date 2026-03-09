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
