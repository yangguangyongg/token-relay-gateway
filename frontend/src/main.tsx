import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Activity, BarChart3, Database, KeyRound, Plus, RefreshCw, Shield, Users } from 'lucide-react';
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
  name: string;
  keyPrefix: string;
  status: string;
  rateLimitPerMinute: number;
};

type ProviderKey = {
  id: string;
  provider: string;
  name: string;
  baseUrl: string;
  status: string;
  priority: number;
};

type UsageRow = {
  provider: string;
  model: string;
  requests: number;
  total_tokens: number;
};

type AuditLog = {
  id: string;
  actor: string;
  action: string;
  target: string;
  details: string;
  createdAt: string;
};

type LoginResponse = {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  username: string;
  roles: string[];
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
  const [auditLogs, setAuditLogs] = useState<AuditLog[]>([]);
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
      const [loadedUsers, loadedApiKeys, loadedProviders, loadedUsage, loadedAuditLogs] = await Promise.all([
        api<User[]>('/api/admin/users', token),
        api<ApiKey[]>('/api/admin/api-keys', token),
        api<ProviderKey[]>('/api/admin/provider-keys', token),
        api<UsageRow[]>('/api/admin/usage-summary', token),
        api<AuditLog[]>('/api/admin/audit-logs', token)
      ]);
      setUsers(loadedUsers);
      setApiKeys(loadedApiKeys);
      setProviderKeys(loadedProviders);
      setUsage(loadedUsage);
      setAuditLogs(loadedAuditLogs);
      setMessage('Ready');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Unknown error');
    }
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
    const response = await api<{ rawKey: string }>('/api/admin/api-keys', adminToken, {
      method: 'POST',
      body: JSON.stringify({
        userId: form.get('userId'),
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
    await api('/api/admin/provider-keys', adminToken, {
      method: 'POST',
      body: JSON.stringify({
        provider: form.get('provider'),
        name: form.get('name'),
        baseUrl: form.get('baseUrl'),
        apiKey: form.get('apiKey'),
        azureDeployment: form.get('azureDeployment') || null,
        priority: Number(form.get('priority'))
      })
    });
    event.currentTarget.reset();
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

      <section className="metrics">
        <Metric icon={<Users />} label="Users" value={totals.users} />
        <Metric icon={<KeyRound />} label="Gateway Keys" value={totals.apiKeys} />
        <Metric icon={<Database />} label="Providers" value={totals.providers} />
        <Metric icon={<Activity />} label="Requests" value={totals.requests} />
      </section>

      <p className="status">{message}</p>
      {rawKey && <p className="secret">New API key: <code>{rawKey}</code></p>}

      <section className="grid">
        <Panel title="Users" icon={<Users />}>
          <form onSubmit={createUser} className="form">
            <input name="email" type="email" placeholder="email@example.com" required />
            <input name="displayName" placeholder="Display name" required />
            <input name="monthlyTokenQuota" type="number" defaultValue="1000000" min="1" required />
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
            <input name="name" placeholder="Key name" required />
            <input name="rateLimitPerMinute" type="number" defaultValue="60" min="1" required />
            <button><Plus size={16} /> Create key</button>
          </form>
          <Table rows={apiKeys} columns={['name', 'keyPrefix', 'status', 'rateLimitPerMinute']} />
        </Panel>

        <Panel title="Provider Keys" icon={<Database />}>
          <form onSubmit={createProvider} className="form provider-form">
            <select name="provider" required>
              <option>OPENAI</option>
              <option>ANTHROPIC</option>
              <option>AZURE_OPENAI</option>
              <option>GEMINI</option>
            </select>
            <input name="name" placeholder="Provider name" required />
            <input name="baseUrl" placeholder="https://api.openai.com" required />
            <input name="apiKey" type="password" placeholder="Provider API key" required />
            <input name="azureDeployment" placeholder="Azure deployment (optional)" />
            <input name="priority" type="number" defaultValue="100" min="0" required />
            <button><Plus size={16} /> Add provider</button>
          </form>
          <Table rows={providerKeys} columns={['provider', 'name', 'baseUrl', 'status', 'priority']} />
        </Panel>

        <Panel title="Usage" icon={<BarChart3 />}>
          <Table rows={usage} columns={['provider', 'model', 'requests', 'total_tokens']} />
        </Panel>

        <Panel title="Audit" icon={<Activity />}>
          <Table rows={auditLogs.slice(-12).reverse()} columns={['actor', 'action', 'target', 'details']} />
        </Panel>
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
