import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Activity, BarChart3, Database, Download, KeyRound, Plus, RefreshCw, Shield, Users } from 'lucide-react';
import './styles.css';

type User = {
  id: string;
  email: string;
  displayName: string;
  status: string;
  monthlyTokenQuota: number;
};

type ApiKey = {
  id: string;
  userId: string;
  workspaceId: string;
  name: string;
  keyPrefix: string;
  status: string;
  rateLimitPerMinute: number;
};

type ProviderKey = {
  id: string;
  ownerUserId: string | null;
  ownerScope: string;
  provider: string;
  name: string;
  baseUrl: string;
  status: string;
  priority: number;
  healthStatus: string;
  lastCheckedAt: string | null;
  lastError: string | null;
  updatedAt: string | null;
};

type UsageRow = {
  provider: string;
  model: string;
  requests: number;
  total_tokens: number;
  total_cost_usd: number;
};

type AuditLog = {
  id: string;
  actor: string;
  action: string;
  target: string;
  details: string;
  createdAt: string;
};

type UsageDetailRow = {
  user_id: string;
  user_email: string;
  user_name: string;
  provider: string;
  model: string;
  requests: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  total_cost_usd: number;
  billing_status: string;
  last_request_at: string;
};

type ModelPricing = {
  id: string;
  provider: string;
  modelPattern: string;
  currency: string;
  promptPricePer1mTokens: number;
  completionPricePer1mTokens: number;
  status: string;
  effectiveFrom: string;
};

type BillingPolicy = {
  userId: string;
  currency: string;
  monthlyBudgetUsd: number;
  alertThresholdPercent: number;
  autoDisableApiKeys: boolean;
  webhookUrl: string | null;
  status: string;
};

type MonthlyBill = {
  bill_id: string;
  bill_month: string;
  status: string;
  currency: string;
  total_requests: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  total_cost_usd: number;
  sent_at: string | null;
  paid_at: string | null;
  user_id: string;
  user_email: string;
  user_name: string;
};

type WorkspaceRow = {
  workspace_id: string;
  workspace_name: string;
  workspace_slug: string;
  workspace_status: string;
  created_at: string;
  member_count: number;
  active_key_count: number;
};

type WorkspaceMemberRow = {
  membership_id: string;
  workspace_id: string;
  user_id: string;
  user_email: string;
  user_name: string;
  role: string;
  status: string;
  created_at: string;
};

type WorkspaceModelConfigRow = {
  id: string;
  workspaceId: string;
  provider: string;
  modelPattern: string;
  enabled: boolean;
  maxTokens: number | null;
  status: string;
  createdByUserId: string;
  createdAt: string;
  updatedAt: string;
};

type LoginResponse = {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  username: string;
  roles: string[];
};

type TabKey = 'overview' | 'workspace' | 'providers' | 'billing' | 'audit';

const currentMonthValue = () => new Date().toISOString().slice(0, 7);

const usageDetailsPath = (month: string, userId: string) => {
  const params = new URLSearchParams();
  params.set('month', month);
  if (userId) {
    params.set('userId', userId);
  }
  return `/api/admin/usage-details?${params.toString()}`;
};

const billingCsvPath = (month: string, userId: string) => {
  const params = new URLSearchParams();
  params.set('month', month);
  if (userId) {
    params.set('userId', userId);
  }
  return `/api/admin/billing/monthly.csv?${params.toString()}`;
};

const billsPath = (month: string) => `/api/admin/bills?month=${encodeURIComponent(month)}`;
const workspacesPath = () => '/api/admin/workspaces';
const workspaceMembersPath = (workspaceId: string) => `/api/admin/workspaces/${encodeURIComponent(workspaceId)}/members`;
const workspaceModelConfigsPath = (workspaceId: string) => `/api/admin/workspaces/${encodeURIComponent(workspaceId)}/model-configs`;

const parseDownloadFileName = (contentDisposition: string | null, fallback: string) => {
  if (!contentDisposition) {
    return fallback;
  }
  const utf8 = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8?.[1]) {
    return decodeURIComponent(utf8[1]);
  }
  const simple = contentDisposition.match(/filename=\"?([^\";]+)\"?/i);
  return simple?.[1] ?? fallback;
};

const api = async <T,>(path: string, adminToken?: string, options: RequestInit = {}): Promise<T> => {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> | undefined)
  };
  if (adminToken) {
    headers.Authorization = `Bearer ${adminToken}`;
  }
  const response = await fetch(path, {
    ...options,
    headers
  });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(body || `Request failed: ${response.status}`);
  }
  return response.json();
};

function App() {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [adminToken, setAdminToken] = useState('');
  const [adminName, setAdminName] = useState('');
  const [adminRoles, setAdminRoles] = useState<string[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [apiKeys, setApiKeys] = useState<ApiKey[]>([]);
  const [providerKeys, setProviderKeys] = useState<ProviderKey[]>([]);
  const [usage, setUsage] = useState<UsageRow[]>([]);
  const [usageDetails, setUsageDetails] = useState<UsageDetailRow[]>([]);
  const [usageMonth, setUsageMonth] = useState(currentMonthValue());
  const [usageUserId, setUsageUserId] = useState('');
  const [billMonth, setBillMonth] = useState(currentMonthValue());
  const [pricingModels, setPricingModels] = useState<ModelPricing[]>([]);
  const [billingPolicies, setBillingPolicies] = useState<BillingPolicy[]>([]);
  const [monthlyBills, setMonthlyBills] = useState<MonthlyBill[]>([]);
  const [workspaces, setWorkspaces] = useState<WorkspaceRow[]>([]);
  const [selectedWorkspaceId, setSelectedWorkspaceId] = useState('');
  const [workspaceMembers, setWorkspaceMembers] = useState<WorkspaceMemberRow[]>([]);
  const [workspaceModelConfigs, setWorkspaceModelConfigs] = useState<WorkspaceModelConfigRow[]>([]);
  const [auditLogs, setAuditLogs] = useState<AuditLog[]>([]);
  const [activeTab, setActiveTab] = useState<TabKey>('overview');
  const [message, setMessage] = useState('');
  const [rawKey, setRawKey] = useState('');

  const totals = useMemo(() => {
    return {
      users: users.length,
      apiKeys: apiKeys.length,
      providers: providerKeys.length,
      requests: usage.reduce((sum, row) => sum + Number(row.requests), 0)
    };
  }, [users, apiKeys, providerKeys, usage]);

  const load = async (tokenOverride?: string) => {
    const token = tokenOverride ?? adminToken;
    if (!token) {
      setMessage('Please login to access admin APIs');
      return;
    }
    setMessage('Loading...');
    try {
      const [
        loadedUsers,
        loadedApiKeys,
        loadedProviders,
        loadedUsage,
        loadedUsageDetails,
        loadedPricingModels,
        loadedBillingPolicies,
        loadedMonthlyBills,
        loadedWorkspaces,
        loadedAuditLogs
      ] = await Promise.all([
        api<User[]>('/api/admin/users', token),
        api<ApiKey[]>('/api/admin/api-keys', token),
        api<ProviderKey[]>('/api/admin/provider-keys', token),
        api<UsageRow[]>('/api/admin/usage-summary', token),
        api<UsageDetailRow[]>(usageDetailsPath(usageMonth, usageUserId), token),
        api<ModelPricing[]>('/api/admin/pricing/models', token),
        api<BillingPolicy[]>('/api/admin/billing/policies', token),
        api<MonthlyBill[]>(billsPath(billMonth), token),
        api<WorkspaceRow[]>(workspacesPath(), token),
        api<AuditLog[]>('/api/admin/audit-logs', token)
      ]);
      setUsers(loadedUsers);
      setApiKeys(loadedApiKeys);
      setProviderKeys(loadedProviders);
      setUsage(loadedUsage);
      setUsageDetails(loadedUsageDetails);
      setPricingModels(loadedPricingModels);
      setBillingPolicies(loadedBillingPolicies);
      setMonthlyBills(loadedMonthlyBills);
      setWorkspaces(loadedWorkspaces);
      setAuditLogs(loadedAuditLogs);
      const nextWorkspaceId = selectedWorkspaceId && loadedWorkspaces.some((item) => item.workspace_id === selectedWorkspaceId)
        ? selectedWorkspaceId
        : (loadedWorkspaces[0]?.workspace_id ?? '');
      setSelectedWorkspaceId(nextWorkspaceId);
      if (nextWorkspaceId) {
        const [membersData, modelConfigData] = await Promise.all([
          api<WorkspaceMemberRow[]>(workspaceMembersPath(nextWorkspaceId), token),
          api<WorkspaceModelConfigRow[]>(workspaceModelConfigsPath(nextWorkspaceId), token)
        ]);
        setWorkspaceMembers(membersData);
        setWorkspaceModelConfigs(modelConfigData);
      } else {
        setWorkspaceMembers([]);
        setWorkspaceModelConfigs([]);
      }
      setMessage('Ready');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Unknown error');
    }
  };

  const loadWorkspaceDetails = async (workspaceId: string, tokenOverride?: string) => {
    const token = tokenOverride ?? adminToken;
    if (!token || !workspaceId) {
      return;
    }
    const [membersData, modelConfigData] = await Promise.all([
      api<WorkspaceMemberRow[]>(workspaceMembersPath(workspaceId), token),
      api<WorkspaceModelConfigRow[]>(workspaceModelConfigsPath(workspaceId), token)
    ]);
    setWorkspaceMembers(membersData);
    setWorkspaceModelConfigs(modelConfigData);
  };

  useEffect(() => {
    setMessage('Please login to access admin APIs');
  }, []);

  const login = async () => {
    setMessage('Signing in...');
    try {
      const response = await api<LoginResponse>('/api/admin/auth/login', undefined, {
        method: 'POST',
        body: JSON.stringify({
          username,
          password
        })
      });
      setAdminToken(response.accessToken);
      setAdminName(response.username);
      setAdminRoles(response.roles);
      setPassword('');
      await load(response.accessToken);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Login failed');
    }
  };

  const logout = () => {
    setAdminToken('');
    setAdminName('');
    setAdminRoles([]);
    setUsers([]);
    setApiKeys([]);
    setProviderKeys([]);
    setUsage([]);
    setUsageDetails([]);
    setPricingModels([]);
    setBillingPolicies([]);
    setMonthlyBills([]);
    setWorkspaces([]);
    setSelectedWorkspaceId('');
    setWorkspaceMembers([]);
    setWorkspaceModelConfigs([]);
    setAuditLogs([]);
    setRawKey('');
    setMessage('Logged out');
  };

  const createUser = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await api('/api/admin/users', adminToken, {
      method: 'POST',
      body: JSON.stringify({
        email: form.get('email'),
        displayName: form.get('displayName'),
        monthlyTokenQuota: Number(form.get('monthlyTokenQuota'))
      })
    });
    event.currentTarget.reset();
    await load();
  };

  const createApiKey = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const workspaceId = (form.get('workspaceId') || '').toString();
    const response = await api<{ rawKey: string }>('/api/admin/api-keys', adminToken, {
      method: 'POST',
      body: JSON.stringify({
        userId: form.get('userId'),
        workspaceId: workspaceId || null,
        name: form.get('name'),
        rateLimitPerMinute: Number(form.get('rateLimitPerMinute'))
      })
    });
    setRawKey(response.rawKey);
    event.currentTarget.reset();
    await load();
  };

  const createProvider = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const ownerUserId = (form.get('ownerUserId') || '').toString();
    await api('/api/admin/provider-keys', adminToken, {
      method: 'POST',
      body: JSON.stringify({
        provider: form.get('provider'),
        name: form.get('name'),
        baseUrl: form.get('baseUrl'),
        apiKey: form.get('apiKey'),
        azureDeployment: form.get('azureDeployment') || null,
        priority: Number(form.get('priority')),
        status: 'ACTIVE',
        ownerUserId: ownerUserId === '__PLATFORM__' ? null : ownerUserId
      })
    });
    event.currentTarget.reset();
    await load();
  };

  const updateProviderSettings = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const providerKeyId = (form.get('providerKeyId') || '').toString();
    if (!providerKeyId) {
      setMessage('Select a provider key first');
      return;
    }

    const ownerChoice = (form.get('ownerUserId') || '').toString();
    const payload: Record<string, unknown> = {
      priority: Number(form.get('priority')),
      status: form.get('status')
    };
    if (ownerChoice === '__PLATFORM__') {
      payload.platformScope = true;
      payload.ownerUserId = null;
    } else if (ownerChoice && ownerChoice !== '__UNCHANGED__') {
      payload.platformScope = false;
      payload.ownerUserId = ownerChoice;
    }

    await api(`/api/admin/provider-keys/${encodeURIComponent(providerKeyId)}`, adminToken, {
      method: 'POST',
      body: JSON.stringify(payload)
    });
    await load();
  };

  const checkProviderKey = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const providerKeyId = (form.get('providerKeyId') || '').toString();
    if (!providerKeyId) {
      setMessage('Select a provider key first');
      return;
    }
    await api(`/api/admin/provider-keys/${encodeURIComponent(providerKeyId)}/check`, adminToken, {
      method: 'POST'
    });
    await load();
  };

  const refreshSelectedWorkspace = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selectedWorkspaceId) {
      setMessage('Select a workspace first');
      return;
    }
    await loadWorkspaceDetails(selectedWorkspaceId);
  };

  const upsertWorkspaceMember = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selectedWorkspaceId) {
      setMessage('Select a workspace first');
      return;
    }
    const form = new FormData(event.currentTarget);
    await api(`/api/admin/workspaces/${encodeURIComponent(selectedWorkspaceId)}/members`, adminToken, {
      method: 'POST',
      body: JSON.stringify({
        userEmail: form.get('userEmail'),
        role: form.get('role'),
        status: form.get('status')
      })
    });
    await loadWorkspaceDetails(selectedWorkspaceId);
  };

  const upsertWorkspaceModelConfig = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selectedWorkspaceId) {
      setMessage('Select a workspace first');
      return;
    }
    const form = new FormData(event.currentTarget);
    const maxTokensRaw = (form.get('maxTokens') || '').toString().trim();
    await api(`/api/admin/workspaces/${encodeURIComponent(selectedWorkspaceId)}/model-configs`, adminToken, {
      method: 'POST',
      body: JSON.stringify({
        provider: form.get('provider'),
        modelPattern: form.get('modelPattern'),
        enabled: form.get('enabled') === 'on',
        maxTokens: maxTokensRaw ? Number(maxTokensRaw) : null,
        status: form.get('status')
      })
    });
    await loadWorkspaceDetails(selectedWorkspaceId);
  };

  const createPricingModel = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await api('/api/admin/pricing/models', adminToken, {
      method: 'POST',
      body: JSON.stringify({
        provider: form.get('provider'),
        modelPattern: form.get('modelPattern'),
        promptPricePer1mTokens: Number(form.get('promptPricePer1mTokens')),
        completionPricePer1mTokens: Number(form.get('completionPricePer1mTokens'))
      })
    });
    event.currentTarget.reset();
    await load();
  };

  const saveBillingPolicy = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await api('/api/admin/billing/policies', adminToken, {
      method: 'POST',
      body: JSON.stringify({
        userId: form.get('userId'),
        monthlyBudgetUsd: Number(form.get('monthlyBudgetUsd')),
        alertThresholdPercent: Number(form.get('alertThresholdPercent')),
        autoDisableApiKeys: form.get('autoDisableApiKeys') === 'on',
        webhookUrl: (form.get('webhookUrl') || '').toString() || null
      })
    });
    await load();
  };

  const generateMonthlyBills = async () => {
    await api(`/api/admin/bills/generate?month=${encodeURIComponent(billMonth)}`, adminToken, {
      method: 'POST'
    });
    await load();
  };

  const updateBillStatus = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const billId = (form.get('billId') || '').toString();
    if (!billId) {
      setMessage('Select a bill to update status');
      return;
    }
    await api(`/api/admin/bills/${encodeURIComponent(billId)}/status`, adminToken, {
      method: 'POST',
      body: JSON.stringify({
        status: form.get('status'),
        note: (form.get('note') || '').toString() || null
      })
    });
    await load();
  };

  const downloadBillingCsv = async () => {
    if (!adminToken) {
      setMessage('Please login first');
      return;
    }
    setMessage('Generating billing CSV...');
    try {
      const response = await fetch(billingCsvPath(usageMonth, usageUserId), {
        headers: {
          Authorization: `Bearer ${adminToken}`
        }
      });
      if (!response.ok) {
        const body = await response.text();
        throw new Error(body || `Request failed: ${response.status}`);
      }

      const blob = await response.blob();
      const downloadName = parseDownloadFileName(
        response.headers.get('content-disposition'),
        `billing-${usageMonth}.csv`
      );
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = downloadName;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      setMessage('Billing CSV downloaded');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Failed to export billing CSV');
    }
  };

  const reloadUsageDetails = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    await load();
  };

  return (
    <main>
      <header className="topbar">
        <div>
          <p className="eyebrow">LLM Gateway</p>
          <h1>Token Relay Admin</h1>
        </div>
        <div className="admin-key">
          <Shield size={18} />
          {!adminToken && (
            <>
              <input
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                aria-label="Admin username"
                placeholder="Username"
              />
              <input
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                aria-label="Admin password"
                type="password"
                placeholder="Password"
              />
              <button onClick={login} title="Sign in">
                Sign in
              </button>
            </>
          )}
          {adminToken && (
            <>
              <span>{adminName} ({adminRoles.join(',')})</span>
              <button onClick={() => load()} title="Refresh dashboard">
                <RefreshCw size={18} />
              </button>
              <button onClick={logout} title="Sign out">
                Sign out
              </button>
            </>
          )}
        </div>
      </header>

      {adminToken && (
        <section className="metrics">
          <Metric icon={<Users />} label="Users" value={totals.users} />
          <Metric icon={<KeyRound />} label="Gateway Keys" value={totals.apiKeys} />
          <Metric icon={<Database />} label="Providers" value={totals.providers} />
          <Metric icon={<Activity />} label="Requests" value={totals.requests} />
        </section>
      )}

      <div className="status-row">
        <p className="status">{message}</p>
        {rawKey && <p className="secret">New API key: <code>{rawKey}</code></p>}
      </div>

      {adminToken && (
        <nav className="tabs" aria-label="Admin sections">
          <TabButton
            icon={<BarChart3 size={16} />}
            label="Overview"
            active={activeTab === 'overview'}
            onClick={() => setActiveTab('overview')}
          />
          <TabButton
            icon={<Users size={16} />}
            label="Workspace"
            active={activeTab === 'workspace'}
            onClick={() => setActiveTab('workspace')}
          />
          <TabButton
            icon={<Database size={16} />}
            label="Providers"
            active={activeTab === 'providers'}
            onClick={() => setActiveTab('providers')}
          />
          <TabButton
            icon={<Shield size={16} />}
            label="Billing"
            active={activeTab === 'billing'}
            onClick={() => setActiveTab('billing')}
          />
          <TabButton
            icon={<Activity size={16} />}
            label="Audit"
            active={activeTab === 'audit'}
            onClick={() => setActiveTab('audit')}
          />
        </nav>
      )}

      <section className="content-stack">
        {!adminToken && (
          <Panel title="Sign In Required" icon={<Shield />}>
            <p className="panel-note">Use your Token Relay admin username and password to access management features.</p>
          </Panel>
        )}

        {adminToken && activeTab === 'overview' && (
          <>
            <Panel title="Users" icon={<Users />}>
              <form onSubmit={createUser} className="form">
                <input name="email" type="email" placeholder="email@example.com" required />
                <input name="displayName" placeholder="Display name" required />
                <input name="monthlyTokenQuota" type="number" defaultValue="1000000" min="1" required />
                <input name="password" type="password" placeholder="Password (optional)" />
                <button><Plus size={16} /> Create user</button>
              </form>
              <Table rows={users} columns={['email', 'displayName', 'status', 'monthlyTokenQuota']} />
            </Panel>

            <Panel title="Gateway API Keys" icon={<KeyRound />}>
              <form onSubmit={createApiKey} className="form">
                <select name="userId" required>
                  <option value="">Select user</option>
                  {users.map((user) => <option key={user.id} value={user.id}>{user.email}</option>)}
                </select>
                <select name="workspaceId">
                  <option value="">Auto-select user workspace</option>
                  {workspaces.map((workspace) => (
                    <option key={workspace.workspace_id} value={workspace.workspace_id}>
                      {workspace.workspace_name}
                    </option>
                  ))}
                </select>
                <input name="name" placeholder="Key name" required />
                <input name="rateLimitPerMinute" type="number" defaultValue="60" min="1" required />
                <button><Plus size={16} /> Create key</button>
              </form>
              <Table rows={apiKeys} columns={['name', 'workspaceId', 'keyPrefix', 'status', 'rateLimitPerMinute']} />
            </Panel>

            <Panel title="Usage Summary" icon={<BarChart3 />}>
              <Table rows={usage} columns={['provider', 'model', 'requests', 'total_tokens', 'total_cost_usd']} />
            </Panel>

            <Panel title="User Usage Details" icon={<BarChart3 />}>
              <form onSubmit={reloadUsageDetails} className="form">
                <input
                  type="month"
                  value={usageMonth}
                  onChange={(event) => setUsageMonth(event.target.value)}
                  required
                />
                <select value={usageUserId} onChange={(event) => setUsageUserId(event.target.value)}>
                  <option value="">All users</option>
                  {users.map((user) => <option key={user.id} value={user.id}>{user.email}</option>)}
                </select>
                <button type="submit">
                  <RefreshCw size={16} />
                  Refresh details
                </button>
                <button type="button" onClick={downloadBillingCsv}>
                  <Download size={16} />
                  Export monthly CSV
                </button>
              </form>
              <Table
                rows={usageDetails}
                columns={['user_email', 'provider', 'model', 'requests', 'prompt_tokens', 'completion_tokens', 'total_tokens', 'total_cost_usd', 'billing_status', 'last_request_at']}
              />
            </Panel>
          </>
        )}

        {adminToken && activeTab === 'workspace' && (
          <>
            <Panel title="Workspaces" icon={<Users />}>
              <form onSubmit={refreshSelectedWorkspace} className="form">
                <select value={selectedWorkspaceId} onChange={(event) => setSelectedWorkspaceId(event.target.value)} required>
                  <option value="">Select workspace</option>
                  {workspaces.map((workspace) => (
                    <option key={workspace.workspace_id} value={workspace.workspace_id}>
                      {workspace.workspace_name} ({workspace.workspace_slug})
                    </option>
                  ))}
                </select>
                <button type="submit">
                  <RefreshCw size={16} />
                  Load workspace config
                </button>
              </form>
              <Table rows={workspaces} columns={['workspace_name', 'workspace_slug', 'workspace_status', 'member_count', 'active_key_count']} />
            </Panel>

            <Panel title="Workspace Members" icon={<Users />}>
              <form onSubmit={upsertWorkspaceMember} className="form">
                <input name="userEmail" type="email" placeholder="member email" required />
                <select name="role" defaultValue="ADMIN" required>
                  <option value="OWNER">OWNER</option>
                  <option value="ADMIN">ADMIN</option>
                  <option value="MEMBER">MEMBER</option>
                </select>
                <select name="status" defaultValue="ACTIVE" required>
                  <option value="ACTIVE">ACTIVE</option>
                  <option value="DISABLED">DISABLED</option>
                </select>
                <button type="submit"><Plus size={16} /> Upsert member</button>
              </form>
              <Table rows={workspaceMembers} columns={['user_email', 'user_name', 'role', 'status']} />
            </Panel>

            <Panel title="Workspace Model Policy" icon={<Shield />}>
              <form onSubmit={upsertWorkspaceModelConfig} className="form">
                <select name="provider" defaultValue="OPENAI" required>
                  <option value="OPENAI">OPENAI</option>
                  <option value="ANTHROPIC">ANTHROPIC</option>
                  <option value="AZURE_OPENAI">AZURE_OPENAI</option>
                  <option value="GEMINI">GEMINI</option>
                </select>
                <input name="modelPattern" placeholder="model pattern, e.g. gpt-4o-mini" required />
                <input name="maxTokens" type="number" min="1" placeholder="max tokens (optional)" />
                <label className="checkbox-field">
                  <input name="enabled" type="checkbox" defaultChecked />
                  <span>Enabled</span>
                </label>
                <select name="status" defaultValue="ACTIVE" required>
                  <option value="ACTIVE">ACTIVE</option>
                  <option value="DISABLED">DISABLED</option>
                </select>
                <button type="submit"><Plus size={16} /> Upsert model config</button>
              </form>
              <Table rows={workspaceModelConfigs} columns={['provider', 'modelPattern', 'enabled', 'maxTokens', 'status']} />
            </Panel>
          </>
        )}

        {adminToken && activeTab === 'providers' && (
          <>
            <Panel title="Provider Keys" icon={<Database />}>
              <form onSubmit={createProvider} className="form">
                <select name="provider" required>
                  <option>OPENAI</option>
                  <option>ANTHROPIC</option>
                  <option>AZURE_OPENAI</option>
                  <option>GEMINI</option>
                </select>
                <select name="ownerUserId" defaultValue="__PLATFORM__" required>
                  <option value="__PLATFORM__">Platform shared</option>
                  {users.map((user) => <option key={user.id} value={user.id}>{user.email}</option>)}
                </select>
                <input name="name" placeholder="Provider name" required />
                <input name="baseUrl" placeholder="https://api.openai.com" required />
                <input name="apiKey" type="password" placeholder="Provider API key" required />
                <input name="azureDeployment" placeholder="Azure deployment (optional)" />
                <input name="priority" type="number" defaultValue="100" min="0" required />
                <button><Plus size={16} /> Add provider</button>
              </form>
              <form onSubmit={updateProviderSettings} className="form">
                <select name="providerKeyId" required>
                  <option value="">Select provider key</option>
                  {providerKeys.map((key) => (
                    <option key={key.id} value={key.id}>
                      {key.provider} / {key.name} / {key.ownerScope}
                    </option>
                  ))}
                </select>
                <input name="priority" type="number" defaultValue="100" min="0" required />
                <select name="status" defaultValue="ACTIVE" required>
                  <option value="ACTIVE">ACTIVE</option>
                  <option value="DISABLED">DISABLED</option>
                </select>
                <select name="ownerUserId" defaultValue="__UNCHANGED__" required>
                  <option value="__UNCHANGED__">Keep owner unchanged</option>
                  <option value="__PLATFORM__">Move to platform shared</option>
                  {users.map((user) => <option key={user.id} value={user.id}>{user.email}</option>)}
                </select>
                <button type="submit">
                  <RefreshCw size={16} />
                  Update provider
                </button>
              </form>
              <form onSubmit={checkProviderKey} className="form">
                <select name="providerKeyId" required>
                  <option value="">Select provider key</option>
                  {providerKeys.map((key) => (
                    <option key={key.id} value={key.id}>
                      {key.provider} / {key.name} / {key.ownerScope}
                    </option>
                  ))}
                </select>
                <button type="submit">
                  <RefreshCw size={16} />
                  Check key status
                </button>
              </form>
              <Table rows={providerKeys} columns={['ownerScope', 'ownerUserId', 'provider', 'name', 'baseUrl', 'status', 'priority', 'healthStatus', 'lastCheckedAt', 'lastError']} />
            </Panel>

            <Panel title="Model Pricing" icon={<Database />}>
              <form onSubmit={createPricingModel} className="form">
                <select name="provider" required>
                  <option>OPENAI</option>
                  <option>ANTHROPIC</option>
                  <option>AZURE_OPENAI</option>
                  <option>GEMINI</option>
                </select>
                <input name="modelPattern" placeholder="model pattern, e.g. gpt-4o-mini or gpt-4o*" required />
                <input name="promptPricePer1mTokens" type="number" step="0.00000001" min="0" placeholder="Prompt price / 1M" required />
                <input name="completionPricePer1mTokens" type="number" step="0.00000001" min="0" placeholder="Completion price / 1M" required />
                <button type="submit"><Plus size={16} /> Add pricing rule</button>
              </form>
              <Table rows={pricingModels} columns={['provider', 'modelPattern', 'promptPricePer1mTokens', 'completionPricePer1mTokens', 'status', 'effectiveFrom']} />
            </Panel>
          </>
        )}

        {adminToken && activeTab === 'billing' && (
          <>
            <Panel title="Billing Policies" icon={<Shield />}>
              <form onSubmit={saveBillingPolicy} className="form">
                <select name="userId" required>
                  <option value="">Select user</option>
                  {users.map((user) => <option key={user.id} value={user.id}>{user.email}</option>)}
                </select>
                <input name="monthlyBudgetUsd" type="number" step="0.00000001" min="0" placeholder="Monthly budget USD" required />
                <input name="alertThresholdPercent" type="number" step="0.01" min="1" max="100" defaultValue="80" required />
                <label className="checkbox-field">
                  <input name="autoDisableApiKeys" type="checkbox" />
                  <span>Auto-disable keys when budget exceeded</span>
                </label>
                <input name="webhookUrl" placeholder="Webhook URL (optional)" />
                <button type="submit"><Plus size={16} /> Save policy</button>
              </form>
              <Table rows={billingPolicies} columns={['userId', 'monthlyBudgetUsd', 'alertThresholdPercent', 'autoDisableApiKeys', 'webhookUrl', 'status']} />
            </Panel>

            <Panel title="Monthly Bills" icon={<BarChart3 />}>
              <form className="form" onSubmit={(event) => { event.preventDefault(); void load(); }}>
                <input type="month" value={billMonth} onChange={(event) => setBillMonth(event.target.value)} required />
                <button type="button" onClick={generateMonthlyBills}>
                  <RefreshCw size={16} />
                  Generate Draft Bills
                </button>
                <button type="submit">
                  <RefreshCw size={16} />
                  Refresh Bills
                </button>
              </form>
              <form onSubmit={updateBillStatus} className="form">
                <select name="billId" required>
                  <option value="">Select bill</option>
                  {monthlyBills.map((bill) => (
                    <option key={bill.bill_id} value={bill.bill_id}>
                      {bill.user_email} / {bill.bill_month} / {bill.status}
                    </option>
                  ))}
                </select>
                <select name="status" required defaultValue="CONFIRMED">
                  <option>CONFIRMED</option>
                  <option>SENT</option>
                  <option>PAID</option>
                </select>
                <input name="note" placeholder="Status note (optional)" />
                <button type="submit"><Plus size={16} /> Update bill status</button>
              </form>
              <Table rows={monthlyBills} columns={['user_email', 'bill_month', 'status', 'total_requests', 'total_tokens', 'total_cost_usd', 'sent_at', 'paid_at']} />
            </Panel>
          </>
        )}

        {adminToken && activeTab === 'audit' && (
          <Panel title="Audit Logs" icon={<Activity />}>
            <Table rows={auditLogs.slice(-20).reverse()} columns={['actor', 'action', 'target', 'details', 'createdAt']} />
          </Panel>
        )}
      </section>
    </main>
  );
}

function Metric({ icon, label, value }: { icon: React.ReactNode; label: string; value: number }) {
  return (
    <div className="metric">
      {icon}
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function TabButton(
  {
    icon,
    label,
    active,
    onClick
  }: {
    icon: React.ReactNode;
    label: string;
    active: boolean;
    onClick: () => void;
  }
) {
  return (
    <button
      type="button"
      className={`tab-button${active ? ' active' : ''}`}
      onClick={onClick}
    >
      {icon}
      <span>{label}</span>
    </button>
  );
}

function Panel({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="panel">
      <h2>{icon}{title}</h2>
      {children}
    </section>
  );
}

function Table<T extends Record<string, unknown>>({ rows, columns }: { rows: T[]; columns: string[] }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>{columns.map((column) => <th key={column}>{column}</th>)}</tr>
        </thead>
        <tbody>
          {rows.length === 0 && <tr><td colSpan={columns.length}>No data</td></tr>}
          {rows.map((row, index) => (
            <tr key={String(row.id ?? index)}>
              {columns.map((column) => <td key={column}>{String(row[column] ?? '')}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

createRoot(document.getElementById('root')!).render(<App />);
