#!C:\Users\guhan\AppData\Local\Programs\Python\Python313\python.exe
#!/usr/bin/env python3
# Run via:  uv run --with paramiko python sshx.py ...
"""SSH helper for the Samsung SM-M176B UserLAnd proot Ubuntu.
Usage:
  sshx.py 'command'            -> run a command, print stdout+stderr
  sshx.py --upload local remote -> scp a file up
  sshx.py --download remote local -> scp a file down
"""
import sys, paramiko

HOST, PORT, USER, PW = "192.168.1.48", 2022, "root", "Hm50TSm3"

def client():
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, PORT, USER, PW, timeout=25, banner_timeout=25, auth_timeout=25)
    return c

def run(cmd, timeout=600):
    c = client()
    _, out, err = c.exec_command(cmd, timeout=timeout)
    o = out.read().decode("utf-8", "replace")
    e = err.read().decode("utf-8", "replace")
    c.close()
    return o, e

def upload(local, remote):
    c = client()
    # pipe binary through exec channel (SFTP subsystem is broken in proot)
    chan = c.get_transport().open_session()
    chan.exec_command(f"cat > {remote}")
    with open(local, "rb") as f:
        while True:
            data = f.read(1 << 20)
            if not data:
                break
            chan.sendall(data)
    chan.shutdown_write()
    # wait + check exit status
    while not chan.exit_status_ready():
        import time; time.sleep(0.2)
    status = chan.recv_exit_status()
    chan.close(); c.close()
    if status != 0:
        raise RuntimeError(f"upload cat exited {status}")

def download(remote, local):
    c = client()
    _, out, _ = c.exec_command(f"cat {remote}")
    with open(local, "wb") as f:
        while True:
            data = out.read(1 << 20)
            if not data:
                break
            f.write(data)
    c.close()

if __name__ == "__main__":
    args = sys.argv[1:]
    if args and args[0] == "--upload":
        upload(args[1], args[2]); print("uploaded", args[1], "->", args[2])
    elif args and args[0] == "--download":
        download(args[1], args[2]); print("downloaded", args[1], "->", args[2])
    else:
        cmd = " ".join(args) if args else "echo hi"
        o, e = run(cmd)
        sys.stdout.write(o)
        if e.strip():
            sys.stderr.write("STDERR:\n" + e)
        if not o.strip() and not e.strip():
            sys.stderr.write("(no output)\n")
