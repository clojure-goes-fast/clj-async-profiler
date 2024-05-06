;; JDK11+-specific tools for attaching the native agent.

(import 'com.sun.tools.attach.VirtualMachine
        'java.lang.ProcessHandle)

(defn- load-agent-path [^VirtualMachine vm, agent-so, command-string]
  (.loadAgentPath vm agent-so command-string))

(defn- vm-attach [^String pid]
  (VirtualMachine/attach pid))

(defn get-self-pid []
  (.pid (java.lang.ProcessHandle/current)))
