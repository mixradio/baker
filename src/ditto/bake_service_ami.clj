(ns ditto.bake-service-ami
  (:require [ditto
             [entertainment-ami :as base]
             [bake-common :refer :all]
             [amis :as amis]
             [onix :as onix]]
            [clj-http.client :as client]
            [clj-time
             [core :as time-core]
             [format :as time-format]]
            [clojure.string :as str]))

(defn service-ami-name
  "Returns the ami name for the service with date/time now"
  [service-name service-version virt-type]
  (str "ent-" service-name "-"
       service-version "-"
       (name virt-type) "-"
       (time-format/unparse
        (time-format/formatter "YYYY-MM-dd_HH-mm-ss")
        (time-core/now))))

(defn motd
  "Set up the message of the day"
  [service-name service-version]
  (cshell (format "echo -e \"\\nEntertainment %s AMI\" >> /etc/motd" service-name)
          (format "echo -e \"\\nBake date: %s\" >> /etc/motd"
                  (time-format/unparse (time-format/formatters :date-time-no-ms) (time-core/now)))
          (format "echo -e \"\\nService: %s %s\\n\" >> /etc/motd" service-name service-version)))

(defn rpm-full-name
  [service-name service-version rpm-name]
  (let [name (or rpm-name service-name)]
    (format "%s-%s.noarch.rpm" name service-version)))

(defn service-rpm
  "Install the service rpm on to the machine"
  [service-name service-version rpm-name]
  (let [rpm-full-name (rpm-full-name service-name service-version rpm-name)]
    (cshell (str "wget -nv http://yumrepo.brislabs.com/ovimusic/" rpm-full-name)
            (str "yum -y install " rpm-full-name)
            (str "rm -fv " rpm-full-name))))

(def numel-on
  "Switch on Numel integration"
  (cshell "yum install -y numel-integration"))

(def puppet-on
  "Enable puppet once we're done"
  (cshell "rm -rf /var/lib/puppet/ssl"
          "chkconfig puppet on"))

(def kill-chroot-prosses
  "Kill all processes in the chroot"
  (cshell "/opt/chrootkiller"))

(defn custom-shell-commands
  "If the service defines custom shell commands "
  [service-name service-version]
  (when-let [commands (onix/shell-commands service-name)]
    (let [version (first (str/split service-version #"-" 2))]
      (->> commands
           (map (fn [c] (str/replace c "{{version}}" version)))
           (apply cshell)))))

(def clear-var-log-messages
  "Clears /var/log/messages"
  (cshell "cat /dev/null > /var/log/messages"))

(def yum-clean-cache
  "Cleans yum's various caches"
  (cshell "yum clean expire-cache"))

(def unlock-puppet-ssh-auth
  "Removes a lock file that suppresses puppet's auth module"
  (cshell "rm -f /var/lock/ditto/ssh"))

(def unlock-puppet-sensu
  "Removes a lock file that suppresses puppet's sensu module"
  (cshell "rm -f /var/lock/ditto/sensu"))

(defn provisioners
  "Returns a list of provisioners for the bake."
  [service-name service-version rpm-name]
  ( ->> [(motd service-name service-version)
         yum-clean-cache
         (service-rpm service-name service-version rpm-name)
         (custom-shell-commands service-name service-version)
         clear-var-log-messages
         numel-on
         unlock-puppet-ssh-auth
         unlock-puppet-sensu
         puppet-on]
        (filter identity)))

(defn service-template
  "Generates a new ami template for the service"
  [service-name service-version rpm-name source-ami virt-type embargo]
  (let [builder (maybe-with-keys
                 {:ami_name (service-ami-name service-name service-version virt-type)
                  :iam_instance_profile "baking"
                  :instance_type (instance-type-for-virt-type virt-type)
                  :region "eu-west-1"
                  :run_tags {:name (format "%s AMI Bake" service-name)
                             :owner "ditto"
                             :description (format "Temp instance used to bake the %s ent ami" service-name)}
                  :tags (merge {:name (format "%s AMI" service-name)
                                :service service-name}
                               (when embargo {:embargo embargo}))
                  :security_group_id "sg-c453b4ab"
                  :source_ami source-ami
                  :ssh_timeout "5m"
                  :ssh_username "ec2-user"
                  :subnet_id (rand-nth ["subnet-bdc08fd5" "subnet-24df904c" "subnet-e6e4e0a0"])
                  :type "amazon-ebs"
                  :vpc_id "vpc-7bc88713"})]
    {:builders [builder]
     :provisioners (provisioners service-name service-version rpm-name)}))

(defn chroot-service-template
  "Generates a new ami template for chroot bake of the service"
  [service-name service-version rpm-name source-ami virt-type embargo]
  (let [builder (maybe-with-keys
                 {:ami_name (service-ami-name service-name service-version virt-type)
                  :tags (merge {:name (format "%s AMI" service-name)
                                :service service-name}
                               (when embargo {:embargo embargo}))
                  :source_ami source-ami
                  :ami_virtualization_type (virtualisation-type-long virt-type)
                  :type "amazon-chroot"})]
    {:builders [builder]
     :provisioners (provisioners service-name service-version rpm-name)}))

(defn create-service-ami
  "Creates a new ami for the supplied service and vesion"
  [service-name service-version rpm-name virt-type embargo]
  (service-template service-name service-version rpm-name
                    (amis/entertainment-base-ami-id virt-type)
                    virt-type embargo))

(defn create-chroot-service-ami
  "Creates a new ami for the supplied service and vesion"
  [service-name service-version rpm-name virt-type embargo]
  (chroot-service-template service-name service-version rpm-name
                           (amis/entertainment-base-ami-id virt-type)
                           virt-type embargo))
