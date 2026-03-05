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

create table if not exists agent_task (
    task_id uuid primary key,
    confirm_token varchar(64) not null unique,
    instance_id uuid not null references claw_instance (id) on delete cascade,
    agent_id varchar(128) not null,
    status varchar(16) not null,
    request_message text not null,
    response_body text null,
    error_message text null,
    expires_at timestamptz not null,
    started_at timestamptz null,
    finished_at timestamptz null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index if not exists idx_agent_task_status on agent_task (status);
create index if not exists idx_agent_task_created_at on agent_task (created_at);
create index if not exists idx_agent_task_expires_at on agent_task (expires_at);

create table if not exists instance_agent_guidance (
    instance_id uuid primary key references claw_instance (id) on delete cascade,
    agents_md text not null,
    enabled boolean not null default true,
    updated_by varchar(128) null,
    updated_at timestamptz not null
);

alter table instance_agent_guidance
    add column if not exists agents_md text;

alter table instance_agent_guidance
    add column if not exists enabled boolean not null default true;

update instance_agent_guidance
set enabled = true
where enabled is null;

alter table instance_agent_guidance
    alter column enabled set not null;

alter table instance_agent_guidance
    add column if not exists updated_by varchar(128) null;

alter table instance_agent_guidance
    add column if not exists updated_at timestamptz;

update instance_agent_guidance
set agents_md = ''
where agents_md is null;

alter table instance_agent_guidance
    alter column agents_md set not null;

update instance_agent_guidance
set updated_at = now()
where updated_at is null;

alter table instance_agent_guidance
    alter column updated_at set not null;

create index if not exists idx_instance_agent_guidance_updated_at on instance_agent_guidance (updated_at);
