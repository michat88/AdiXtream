import React, { useState, useRef, useEffect } from 'react';
import { 
  StyleSheet, Text, View, TextInput, TouchableOpacity, ScrollView, Animated, Alert, SafeAreaView,
  Platform, StatusBar, KeyboardAvoidingView, ActivityIndicator, Modal
} from 'react-native';

import { Ionicons } from '@expo/vector-icons';
import * as Clipboard from 'expo-clipboard';

// === URL DATABASE FIREBASE KAMU ===
const FIREBASE_URL = "https://adixtream-premium-default-rtdb.asia-southeast1.firebasedatabase.app/";

export default function UnlockManager({ onBack }) {
  const [activeTab, setActiveTab] = useState('generator');
  const [deviceId, setDeviceId] = useState('');
  const [selectedDays, setSelectedDays] = useState(0);
  const [customDays, setCustomDays] = useState('');
  const [isCustom, setIsCustom] = useState(false);
  const [result, setResult] = useState(null); 
  const [errorMsg, setErrorMsg] = useState('');
  
  // State Database
  const [users, setUsers] = useState({});
  const [isLoadingDB, setIsLoadingDB] = useState(false);
  const [searchQuery, setSearchQuery] = useState(''); 

  // === STATE BARU UNTUK MENU DETAIL ===
  const [detailModalVisible, setDetailModalVisible] = useState(false);
  const [selectedUser, setSelectedUser] = useState(null);
  const [editDaysInput, setEditDaysInput] = useState(''); // Untuk fitur koreksi hari

  const errorOpacity = useRef(new Animated.Value(0)).current;
  const glowAnim = useRef(new Animated.Value(0.3)).current;

  useEffect(() => {
    Animated.loop(
      Animated.sequence([
        Animated.timing(glowAnim, { toValue: 1, duration: 1000, useNativeDriver: true }),
        Animated.timing(glowAnim, { toValue: 0.3, duration: 1000, useNativeDriver: true }),
      ])
    ).start();
  }, []);

  const showError = (msg) => {
    setErrorMsg(msg);
    Animated.sequence([
      Animated.timing(errorOpacity, { toValue: 1, duration: 300, useNativeDriver: true }),
      Animated.delay(3000),
      Animated.timing(errorOpacity, { toValue: 0, duration: 300, useNativeDriver: true })
    ]).start(() => setErrorMsg(''));
  };

  const handleClear = () => {
    setDeviceId(''); setSelectedDays(0); setCustomDays('');
    setIsCustom(false); setResult(null); setErrorMsg('');
  };

  const handleSelectPackage = (days) => { setSelectedDays(days); setIsCustom(false); setCustomDays(''); };
  const handleToggleCustom = () => { setSelectedDays(0); setIsCustom(true); };

  const generateRandomCode = () => {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    let rand = '';
    for (let i = 0; i < 6; i++) {
      rand += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return rand;
  };

  const generateNewCode = async () => {
    const currentDeviceId = deviceId.trim().toUpperCase();
    let days = selectedDays;
    if (isCustom) days = parseInt(customDays);
    if (!currentDeviceId) return showError("Device ID tidak boleh kosong!");
    if (!days || days <= 0 || isNaN(days)) return showError("Pilih paket atau masukkan jumlah hari!");

    try {
      const checkRes = await fetch(`${FIREBASE_URL}users/${currentDeviceId}.json`);
      const existingData = await checkRes.json();
      
      if (existingData && existingData.code) {
        Alert.alert(
          "Peringatan",
          "Device ID ini sudah terdaftar! Jika Anda klik 'Buat Baru', kode lama mereka akan HANGUS. Gunakan tombol 'PERPANJANG' jika hanya ingin menambah masa aktif.",
          [
            { text: "Batal", style: "cancel" },
            { text: "Tetap Buat Baru", style: "destructive", onPress: () => executeGenerateNew(currentDeviceId, days) }
          ]
        );
      } else {
        executeGenerateNew(currentDeviceId, days);
      }
    } catch (e) { showError("Terjadi kesalahan sistem/koneksi."); }
  };

  const executeGenerateNew = async (currentDeviceId, days) => {
    try {
      const baseDate = new Date();
      baseDate.setDate(baseDate.getDate() + days);
      const expiredAtTimestamp = baseDate.getTime(); 
      const finalCode = generateRandomCode();
      const expDateString = baseDate.toLocaleDateString('id-ID', { day: 'numeric', month: 'long', year: 'numeric' });

      setResult({ type: 'new', days: days, expDate: expDateString, code: finalCode });

      await fetch(`${FIREBASE_URL}users/${currentDeviceId}.json`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
          status: 'aktif', 
          code: finalCode,
          expired_at: expiredAtTimestamp,
          extend_count: 0, 
          last_update: new Date().toISOString() 
        })
      });
    } catch (e) { showError("Gagal menyimpan ke server."); }
  };

  const extendCode = async () => {
    const currentDeviceId = deviceId.trim().toUpperCase();
    let days = selectedDays;
    if (isCustom) days = parseInt(customDays);
    if (!currentDeviceId) return showError("Device ID tidak boleh kosong!");
    if (!days || days <= 0 || isNaN(days)) return showError("Pilih paket atau masukkan jumlah hari!");

    // === TAMBAHAN POPUP KONFIRMASI PERPANJANG DI SINI ===
    Alert.alert(
      "Konfirmasi Perpanjang",
      `Yakin ingin memperpanjang akses untuk Device ID: ${currentDeviceId} selama ${days} hari?`,
      [
        { text: "Batal", style: "cancel" },
        {
          text: "Ya, Perpanjang",
          onPress: async () => {
            try {
              const checkRes = await fetch(`${FIREBASE_URL}users/${currentDeviceId}.json`);
              const existingData = await checkRes.json();

              if (!existingData || !existingData.code) {
                return showError("Gagal! User ini belum punya kode. Silakan klik 'BUAT BARU'.");
              }

              let baseTimestamp = (existingData.expired_at && existingData.expired_at > Date.now()) 
                                  ? existingData.expired_at 
                                  : Date.now();
              
              const baseDate = new Date(baseTimestamp);
              baseDate.setDate(baseDate.getDate() + days); 
              
              const newExpiredTimestamp = baseDate.getTime();
              const newExtendCount = (existingData.extend_count || 0) + 1; 

              const expDateString = baseDate.toLocaleDateString('id-ID', { day: 'numeric', month: 'long', year: 'numeric' });

              setResult({ type: 'extend', days: days, expDate: expDateString, code: existingData.code });

              await fetch(`${FIREBASE_URL}users/${currentDeviceId}.json`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ 
                  status: 'aktif', 
                  expired_at: newExpiredTimestamp,
                  extend_count: newExtendCount,
                  last_update: new Date().toISOString() 
                })
              });

            } catch (e) { showError("Terjadi kesalahan sistem/koneksi."); }
          }
        }
      ]
    );
  };

  const copyCode = async (textToCopy = result?.code) => {
    if (textToCopy) {
      try {
        await Clipboard.setStringAsync(textToCopy);
        Alert.alert("Sukses", `Berhasil disalin: ${textToCopy}`);
      } catch (err) { Alert.alert("Gagal", "Gagal menyalin otomatis."); }
    }
  };

  const fetchUsersFromDB = async () => {
    setIsLoadingDB(true);
    try {
      const response = await fetch(`${FIREBASE_URL}users.json`);
      const data = await response.json();
      setUsers(data || {});
    } catch (error) {
      Alert.alert("Koneksi Gagal", "Gagal mengambil data dari server Firebase.");
    }
    setIsLoadingDB(false);
  };

  const handleTabChange = (tab) => {
    setActiveTab(tab);
    if (tab === 'database') {
      setSearchQuery(''); 
      fetchUsersFromDB();
    }
  };

  // === FUNGSI BARU UNTUK MENU DETAIL ===
  const openDetailModal = (id, data) => {
    setSelectedUser({ id, ...data });
    setEditDaysInput(''); // Kosongkan input koreksi hari saat modal dibuka
    setDetailModalVisible(true);
  };

  const closeDetailModal = () => {
    setDetailModalVisible(false);
    setSelectedUser(null);
  };

  const toggleUserStatus = (id, currentStatus) => {
    const isBanned = currentStatus === 'banned';
    const actionText = isBanned ? 'MENGAKTIFKAN KEMBALI' : 'MENCABUT (BANNED)';
    const newStatus = isBanned ? 'aktif' : 'banned';
    
    Alert.alert(
      "Konfirmasi Keamanan",
      `Yakin ingin ${actionText} akses untuk Device ID: ${id}?`,
      [
        { text: "Batal", style: "cancel" },
        { 
          text: "Ya, Eksekusi", 
          style: isBanned ? "default" : "destructive",
          onPress: async () => {
            // Update lokal
            setUsers(prev => ({ ...prev, [id]: { ...prev[id], status: newStatus } }));
            if(selectedUser) setSelectedUser({...selectedUser, status: newStatus}); // Update di modal juga
            try {
              await fetch(`${FIREBASE_URL}users/${id}.json`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ status: newStatus, last_update: new Date().toISOString() })
              });
            } catch (error) {
              Alert.alert("Error", "Gagal menyimpan perubahan ke server.");
              fetchUsersFromDB();
            }
          }
        }
      ]
    );
  };

  const deleteUser = (id) => {
    Alert.alert(
      "Hapus Data Permanen",
      `Yakin ingin menghapus Device ID ${id} secara permanen dari Database?`,
      [
        { text: "Batal", style: "cancel" },
        { 
          text: "Ya, Hapus!", 
          style: "destructive",
          onPress: async () => {
            // Hapus dari UI sementara
            const newUsers = { ...users };
            delete newUsers[id];
            setUsers(newUsers);
            closeDetailModal(); // Tutup modal karena data sudah dihapus

            try {
              await fetch(`${FIREBASE_URL}users/${id}.json`, { method: 'DELETE' });
              Alert.alert("Sukses", "Data berhasil dihapus permanen.");
            } catch (error) {
              Alert.alert("Error", "Gagal menghapus data dari server.");
              fetchUsersFromDB(); 
            }
          }
        }
      ]
    );
  };

  // Fungsi untuk mengkoreksi sisa hari (merubah expiry date)
  const handleUpdateExpiry = async () => {
    const days = parseInt(editDaysInput);
    if (!editDaysInput || isNaN(days) || days < 0) {
      return Alert.alert("Input Tidak Valid", "Masukkan angka hari yang valid (minimal 0).");
    }

    Alert.alert(
      "Konfirmasi Koreksi",
      `Yakin ingin mengatur ulang sisa waktu menjadi tepat ${days} hari dari sekarang?`,
      [
        { text: "Batal", style: "cancel" },
        {
          text: "Ya, Update",
          onPress: async () => {
            const baseDate = new Date();
            baseDate.setDate(baseDate.getDate() + days); // Hitung dari hari ini + hari yang diinput
            const newExpiredTimestamp = baseDate.getTime();

            // Update lokal agar UI langsung berubah
            setUsers(prev => ({ ...prev, [selectedUser.id]: { ...prev[selectedUser.id], expired_at: newExpiredTimestamp } }));
            setSelectedUser({...selectedUser, expired_at: newExpiredTimestamp});
            setEditDaysInput(''); // reset input

            try {
              await fetch(`${FIREBASE_URL}users/${selectedUser.id}.json`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ 
                  expired_at: newExpiredTimestamp,
                  last_update: new Date().toISOString() 
                })
              });
              Alert.alert("Sukses", `Masa aktif berhasil diperbarui menjadi sisa ${days} hari.`);
            } catch (error) {
              Alert.alert("Error", "Gagal menyimpan perubahan ke server.");
              fetchUsersFromDB();
            }
          }
        }
      ]
    );
  };

  // Fungsi Pembantu Tampilan Hari
  const renderExpiryInfo = (expiredAt) => {
    if (!expiredAt) return null;
    const expDate = new Date(expiredAt);
    const dateString = expDate.toLocaleDateString('id-ID', { day: 'numeric', month: 'short', year: 'numeric' });
    const diffTime = expiredAt - Date.now();
    const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
    const isExpired = diffDays <= 0;
    const daysText = isExpired ? 'Habis (Expired)' : `Sisa ${diffDays} Hari`;
    const daysStyle = isExpired ? styles.textExpired : styles.textHighlight;

    return (
      <View style={{ marginTop: 4 }}>
        <Text style={styles.userExpiryText}>
          Exp: {dateString} • <Text style={daysStyle}>{daysText}</Text>
        </Text>
      </View>
    );
  };

  const getRemainingDays = (expiredAt) => {
    if (!expiredAt) return 0;
    const diffTime = expiredAt - Date.now();
    const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
    return diffDays > 0 ? diffDays : 0;
  };

  const filteredAndSortedUsers = Object.entries(users)
    .filter(([id]) => id.includes(searchQuery.toUpperCase())) 
    .sort((a, b) => {
      const dateA = new Date(a[1].last_update || 0).getTime();
      const dateB = new Date(b[1].last_update || 0).getTime();
      return dateB - dateA;
    });

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="light-content" backgroundColor="#0f172a" />
      <View style={styles.topNav}>
        <TouchableOpacity onPress={onBack} style={styles.backBtn}>
          <Text style={styles.backBtnText}>{"< KEMBALI KE DASHBOARD"}</Text>
        </TouchableOpacity>
      </View>

      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : 'height'} style={{ flex: 1 }}>
        <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
          
          <View style={styles.card}>
            <View style={styles.headerContainer}>
              <Animated.Text style={[styles.titleGlow, { opacity: glowAnim }]}>
                ADIXTREAM MANAGER
              </Animated.Text>
              
              <View style={styles.statusContainer}>
                <View style={styles.statusDot} />
                <Text style={styles.statusText}>SERVER ONLINE</Text>
              </View>
            </View>

            <View style={styles.tabContainer}>
              <TouchableOpacity style={[styles.tabBtn, activeTab === 'generator' && styles.tabBtnActive]} onPress={() => handleTabChange('generator')}>
                <Text style={[styles.tabText, activeTab === 'generator' && styles.tabTextActive]}>GENERATOR</Text>
              </TouchableOpacity>
              
              <TouchableOpacity style={[styles.tabBtn, activeTab === 'database' && styles.tabBtnActive]} onPress={() => handleTabChange('database')}>
                <Text style={[styles.tabText, activeTab === 'database' && styles.tabTextActive]}>DATABASE</Text>
              </TouchableOpacity>
            </View>

            {/* TAB GENERATOR */}
            {activeTab === 'generator' && (
              <View>
                <View style={styles.formGroup}>
                  <Text style={styles.label}>DEVICE ID</Text>
                  <TextInput style={styles.input} placeholder="Masukkan Device ID User" placeholderTextColor="#64748b" value={deviceId} onChangeText={(text) => setDeviceId(text.toUpperCase())} autoCapitalize="characters" />
                </View>

                <View style={styles.formGroup}>
                  <Text style={styles.label}>PILIH PAKET</Text>
                  <View style={styles.gridContainer}>
                    <TouchableOpacity style={[styles.pkgBtn, selectedDays === 30 && !isCustom && styles.pkgBtnActive]} onPress={() => handleSelectPackage(30)}>
                      <Text style={styles.pkgTitle}>1 BULAN</Text>
                      <Text style={styles.pkgPrice}>Rp 10.000</Text>
                    </TouchableOpacity>
                    <TouchableOpacity style={[styles.pkgBtn, selectedDays === 180 && !isCustom && styles.pkgBtnActive]} onPress={() => handleSelectPackage(180)}>
                      <Text style={styles.pkgTitle}>6 BULAN</Text>
                      <Text style={styles.pkgPrice}>Rp 30.000</Text>
                    </TouchableOpacity>
                    <TouchableOpacity style={[styles.pkgBtn, selectedDays === 365 && !isCustom && styles.pkgBtnActive]} onPress={() => handleSelectPackage(365)}>
                      <Text style={styles.pkgTitle}>1 TAHUN</Text>
                      <Text style={styles.pkgPrice}>Rp 50.000</Text>
                    </TouchableOpacity>
                    <TouchableOpacity style={[styles.pkgBtn, isCustom && styles.pkgBtnCustomActive]} onPress={handleToggleCustom}>
                      <Text style={styles.pkgTitleCustom}>MANUAL</Text>
                      <Text style={styles.pkgPrice}>Custom Hari</Text>
                    </TouchableOpacity>
                  </View>

                  {isCustom && (
                    <TextInput style={[styles.input, { marginTop: 10 }]} placeholder="Jumlah Hari (Contoh: 31)" placeholderTextColor="#64748b" keyboardType="numeric" value={customDays} onChangeText={setCustomDays} />
                  )}
                </View>

                <View style={styles.actionButtonsRow}>
                  <TouchableOpacity style={styles.clearBtn} onPress={handleClear}>
                    <Ionicons name="trash-outline" size={22} color="#f87171" />
                  </TouchableOpacity>
                  <TouchableOpacity style={styles.btnGenerateNew} onPress={generateNewCode} activeOpacity={0.8}>
                    <Text style={styles.btnGenerateText}>BUAT BARU</Text>
                  </TouchableOpacity>
                  <TouchableOpacity style={styles.btnExtend} onPress={extendCode} activeOpacity={0.8}>
                    <Text style={styles.btnExtendText}>PERPANJANG</Text>
                  </TouchableOpacity>
                </View>

                {errorMsg !== '' && (
                  <Animated.View style={[styles.errorBox, { opacity: errorOpacity }]}>
                    <Text style={styles.errorText}>{errorMsg}</Text>
                  </Animated.View>
                )}

                {result && (
                  <View style={styles.resultArea}>
                    <View style={styles.resultBox}>
                      <View style={styles.resultHeader}>
                        <Text style={styles.resultSubText}>Ditambahkan: <Text style={styles.textYellow}>+{result.days} Hari</Text></Text>
                        <Text style={styles.resultSubText}>Exp: <Text style={styles.textRed}>{result.expDate}</Text></Text>
                      </View>
                      
                      <Text style={styles.codeLabel}>
                        {result.type === 'extend' ? "KODE UNLOCK (DIPERPANJANG)" : "KODE UNLOCK BARU"}
                      </Text>
                      
                      <View style={styles.codeContainer}>
                        <Text style={styles.finalCode} selectable={true}>{result.code}</Text>
                        <TouchableOpacity style={styles.copyBtn} onPress={() => copyCode(result.code)}>
                          <Ionicons name="copy-outline" size={18} color="#f8fafc" />
                        </TouchableOpacity>
                      </View>
                    </View>
                    <Text style={styles.footerNote}>
                      {result.type === 'extend' 
                        ? "*Masa aktif ditambah & poin loyalti naik" 
                        : "*Kode online baru berhasil didaftarkan"}
                    </Text>
                  </View>
                )}
              </View>
            )}

            {/* TAB DATABASE */}
            {activeTab === 'database' && (
              <View style={styles.databaseContainer}>
                <View style={styles.dbHeaderRow}>
                  <Text style={styles.dbTitle}>Daftar Device ID</Text>
                  <TouchableOpacity onPress={fetchUsersFromDB} style={styles.refreshBtn}>
                    <Ionicons name="refresh" size={18} color="#22d3ee" />
                  </TouchableOpacity>
                </View>

                <View style={styles.searchContainer}>
                  <Ionicons name="search" size={18} color="#64748b" style={styles.searchIcon} />
                  <TextInput 
                    style={styles.searchInput} 
                    placeholder="Cari Device ID..." 
                    placeholderTextColor="#64748b" 
                    value={searchQuery} 
                    onChangeText={(text) => setSearchQuery(text.toUpperCase())} 
                  />
                  {searchQuery !== '' && (
                    <TouchableOpacity onPress={() => setSearchQuery('')}>
                      <Ionicons name="close-circle" size={18} color="#94a3b8" />
                    </TouchableOpacity>
                  )}
                </View>

                {isLoadingDB ? (
                  <ActivityIndicator size="large" color="#06b6d4" style={{ marginTop: 30 }} />
                ) : (
                  filteredAndSortedUsers.length === 0 ? (
                    <Text style={styles.emptyText}>Tidak ada data ditemukan.</Text>
                  ) : (
                    <View style={styles.listContainer}>
                      {filteredAndSortedUsers.map(([id, data]) => {
                        return (
                          <View key={id} style={styles.userRow}>
                            <View style={styles.userInfo}>
                              <Text style={styles.userIdText} selectable={true}>{id}</Text>
                              {data.code && <Text style={styles.userCodeText}>Kode: {data.code}</Text>}
                              {renderExpiryInfo(data.expired_at)}
                            </View>
                            
                            <View style={styles.actionRowContainer}>
                              <TouchableOpacity 
                                style={styles.btnOpenDetail}
                                onPress={() => openDetailModal(id, data)}
                              >
                                <Ionicons name="options-outline" size={14} color="#f8fafc" style={{marginRight: 4}}/>
                                <Text style={styles.btnOpenDetailText}>DETAIL</Text>
                              </TouchableOpacity>
                            </View>
                          </View>
                        );
                      })}
                    </View>
                  )
                )}
              </View>
            )}

          </View>
        </ScrollView>
      </KeyboardAvoidingView>

      {/* ========================================================= */}
      {/* ============= MODAL DETAIL USER (POPUP MENU) ============ */}
      {/* ========================================================= */}
      <Modal
        animationType="fade"
        transparent={true}
        visible={detailModalVisible}
        onRequestClose={closeDetailModal}
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            {selectedUser && (
              <>
                <View style={styles.modalHeader}>
                  <Text style={styles.modalTitle}>Detail Pengguna</Text>
                  <TouchableOpacity onPress={closeDetailModal} style={styles.closeModalBtn}>
                    <Ionicons name="close" size={24} color="#94a3b8" />
                  </TouchableOpacity>
                </View>

                {/* Info Basic */}
                <View style={styles.modalInfoBox}>
                  <View style={styles.modalInfoRow}>
                    <Text style={styles.modalLabel}>Device ID:</Text>
                    <View style={{flexDirection: 'row', alignItems: 'center'}}>
                      <Text style={styles.modalValue} selectable={true}>{selectedUser.id}</Text>
                      <TouchableOpacity onPress={() => copyCode(selectedUser.id)} style={{marginLeft: 8}}>
                         <Ionicons name="copy-outline" size={16} color="#06b6d4" />
                      </TouchableOpacity>
                    </View>
                  </View>
                  <View style={styles.modalInfoRow}>
                    <Text style={styles.modalLabel}>Kode Unlock:</Text>
                    <View style={{flexDirection: 'row', alignItems: 'center'}}>
                      <Text style={[styles.modalValue, {color: '#4ade80'}]} selectable={true}>{selectedUser.code || '-'}</Text>
                      <TouchableOpacity onPress={() => copyCode(selectedUser.code)} style={{marginLeft: 8}}>
                         <Ionicons name="copy-outline" size={16} color="#06b6d4" />
                      </TouchableOpacity>
                    </View>
                  </View>
                  <View style={styles.modalInfoRow}>
                    <Text style={styles.modalLabel}>Status:</Text>
                    <Text style={[styles.modalValue, selectedUser.status === 'banned' ? {color: '#f87171'} : {color: '#4ade80'}]}>
                      {selectedUser.status === 'banned' ? 'BANNED' : 'AKTIF'}
                    </Text>
                  </View>
                  <View style={styles.modalInfoRow}>
                    <Text style={styles.modalLabel}>Total Perpanjang:</Text>
                    <Text style={styles.modalValue}>{selectedUser.extend_count || 0} Kali</Text>
                  </View>
                </View>

                {/* Koreksi Sisa Hari */}
                <View style={styles.correctionBox}>
                  <Text style={styles.correctionTitle}>Koreksi Masa Aktif (Sisa Hari)</Text>
                  <Text style={styles.correctionDesc}>
                    Saat ini tersisa: <Text style={{fontWeight: 'bold', color: '#facc15'}}>{getRemainingDays(selectedUser.expired_at)} Hari</Text>
                  </Text>
                  <Text style={styles.correctionDescSmall}>
                    Jika salah input perpanjangan, masukkan jumlah sisa hari yang benar di bawah ini lalu klik Update.
                  </Text>
                  
                  <View style={styles.correctionInputRow}>
                    <TextInput 
                      style={styles.inputKoreksi} 
                      placeholder="Contoh: 30" 
                      placeholderTextColor="#64748b" 
                      keyboardType="numeric" 
                      value={editDaysInput} 
                      onChangeText={setEditDaysInput} 
                    />
                    <TouchableOpacity style={styles.btnUpdateKoreksi} onPress={handleUpdateExpiry}>
                      <Text style={styles.btnUpdateKoreksiText}>UPDATE HARI</Text>
                    </TouchableOpacity>
                  </View>
                </View>

                {/* Tombol Aksi Bahaya */}
                <View style={styles.modalActionBox}>
                  <TouchableOpacity 
                    style={[styles.modalActionBtn, selectedUser.status === 'banned' ? styles.btnAktif : styles.btnBanned, {marginBottom: 12}]}
                    onPress={() => toggleUserStatus(selectedUser.id, selectedUser.status)}
                  >
                    <Ionicons name={selectedUser.status === 'banned' ? "checkmark-circle-outline" : "ban-outline"} size={18} color="#f8fafc" style={{marginRight: 6}}/>
                    <Text style={styles.modalActionText}>
                      {selectedUser.status === 'banned' ? 'AKTIFKAN KEMBALI AKSES' : 'CABUT AKSES (BANNED)'}
                    </Text>
                  </TouchableOpacity>

                  <TouchableOpacity 
                    style={styles.btnDeletePermanen}
                    onPress={() => deleteUser(selectedUser.id)}
                  >
                    <Ionicons name="trash-outline" size={18} color="#f8fafc" style={{marginRight: 6}}/>
                    <Text style={styles.modalActionText}>HAPUS DATA PERMANEN</Text>
                  </TouchableOpacity>
                </View>

              </>
            )}
          </View>
        </View>
      </Modal>
      {/* ========================================================= */}

    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: { flex: 1, backgroundColor: '#0f172a' },
  topNav: { paddingHorizontal: 16, paddingTop: Platform.OS === 'android' ? StatusBar.currentHeight + 12 : 12, paddingBottom: 10, backgroundColor: '#0f172a' },
  backBtn: { alignSelf: 'flex-start', paddingVertical: 8, paddingHorizontal: 12, backgroundColor: 'rgba(6, 182, 212, 0.1)', borderRadius: 8, borderWidth: 1, borderColor: 'rgba(6, 182, 212, 0.3)' },
  backBtnText: { color: '#22d3ee', fontWeight: 'bold', fontSize: 12, letterSpacing: 1 },
  container: { flexGrow: 1, justifyContent: 'center', alignItems: 'center', padding: 16, paddingBottom: 70 },
  card: { width: '100%', maxWidth: 450, backgroundColor: '#1e293b', borderRadius: 12, padding: 24, borderColor: 'rgba(6, 182, 212, 0.5)', borderWidth: 1, shadowColor: '#06b6d4', shadowOffset: { width: 0, height: 0 }, shadowOpacity: 0.5, shadowRadius: 10, elevation: 10 },
  headerContainer: { alignItems: 'center', marginBottom: 20 },
  titleGlow: { fontSize: 22, fontWeight: 'bold', color: '#22d3ee', textShadowColor: '#06b6d4', textShadowOffset: { width: 0, height: 0 }, textShadowRadius: 12, marginBottom: 8 }, 
  statusContainer: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center' },
  statusDot: { width: 10, height: 10, borderRadius: 5, backgroundColor: '#22c55e', marginRight: 6 },
  statusText: { fontSize: 12, color: '#4ade80', fontWeight: 'bold', letterSpacing: 1 },
  tabContainer: { flexDirection: 'row', backgroundColor: '#0f172a', borderRadius: 8, pading: 4, marginBottom: 20, borderWidth: 1, borderColor: '#334155' },
  tabBtn: { flex: 1, paddingVertical: 12, alignItems: 'center', borderRadius: 6 },
  tabBtnActive: { backgroundColor: '#334155' },
  tabText: { color: '#64748b', fontSize: 13, fontWeight: 'bold' },
  tabTextActive: { color: '#22d3ee' },
  formGroup: { marginBottom: 20 },
  label: { color: '#a5f3fc', fontSize: 14, fontWeight: 'bold', marginBottom: 8 },
  input: { backgroundColor: '#0f172a', borderWidth: 1, borderColor: '#334155', color: '#ffffff', padding: 12, borderRadius: 6, fontSize: 16 },
  gridContainer: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between' },
  pkgBtn: { width: '48%', borderWidth: 1, borderColor: '#475569', padding: 12, borderRadius: 6, alignItems: 'center', marginBottom: 10 },
  pkgBtnActive: { backgroundColor: '#334155', borderColor: '#06b6d4' },
  pkgBtnCustomActive: { backgroundColor: '#334155', borderColor: '#eab308' },
  pkgTitle: { fontWeight: 'bold', color: '#22d3ee', marginBottom: 4 },
  pkgTitleCustom: { fontWeight: 'bold', color: '#facc15', marginBottom: 4 },
  pkgPrice: { color: '#94a3b8', fontSize: 12 },
  actionButtonsRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 10 },
  clearBtn: { backgroundColor: 'rgba(239, 68, 68, 0.1)', paddingVertical: 12, paddingHorizontal: 12, borderRadius: 6, alignItems: 'center', justifyContent: 'center', marginRight: 10, borderWidth: 1, borderColor: 'rgba(239, 68, 68, 0.5)' },
  btnGenerateNew: { flex: 1, backgroundColor: '#0891b2', paddingVertical: 14, borderRadius: 6, alignItems: 'center', justifyContent: 'center', marginRight: 10 },
  btnGenerateText: { color: '#ffffff', fontWeight: 'bold', fontSize: 12 },
  btnExtend: { flex: 1, backgroundColor: '#ca8a04', paddingVertical: 14, borderRadius: 6, alignItems: 'center', justifyContent: 'center' },
  btnExtendText: { color: '#ffffff', fontWeight: 'bold', fontSize: 12 },
  errorBox: { marginTop: 16, backgroundColor: 'rgba(127, 29, 29, 0.3)', borderWidth: 1, borderColor: 'rgba(239, 68, 68, 0.5)', padding: 12, borderRadius: 6 },
  errorText: { color: '#fecaca', textAlign: 'center', fontSize: 14 },
  resultArea: { marginTop: 24, paddingTop: 24, borderTopWidth: 1, borderTopColor: '#334155' },
  resultBox: { backgroundColor: 'rgba(0,0,0,0.5)', padding: 16, borderRadius: 6, borderWidth: 1, borderColor: 'rgba(20, 83, 45, 0.5)' },
  resultHeader: { flexDirection: 'column', alignItems: 'center', marginBottom: 12 },
  resultSubText: { color: '#94A3B8', fontSize: 12, marginVertical: 2 },
  textYellow: { color: '#facc15', fontWeight: 'bold' },
  textRed: { color: '#f87171', fontWeight: 'bold' },
  codeLabel: { textAlign: 'center', color: '#64748b', fontSize: 12, letterSpacing: 2, marginBottom: 8 },
  codeContainer: { flexDirection: 'row', justifyContent: 'center', alignItems: 'center', position: 'relative' },
  finalCode: { fontSize: 32, fontWeight: 'bold', color: '#4ade80', letterSpacing: 4, textAlign: 'center' },
  copyBtn: { position: 'absolute', right: 0, backgroundColor: '#334155', paddingHorizontal: 10, paddingVertical: 8, borderRadius: 8, justifyContent: 'center', alignItems: 'center' },
  footerNote: { textAlign: 'center', color: '#4ade80', fontSize: 11, marginTop: 12 },
  databaseContainer: { flex: 1 },
  dbHeaderRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 },
  dbTitle: { color: '#a5f3fc', fontSize: 14, fontWeight: 'bold' },
  refreshBtn: { padding: 4 },
  searchContainer: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#0f172a', borderWidth: 1, borderColor: '#334155', borderRadius: 8, paddingHorizontal: 12, paddingVertical: 8, marginBottom: 16 },
  searchIcon: { marginRight: 8 },
  searchInput: { flex: 1, color: '#f8fafc', fontSize: 14 },
  emptyText: { color: '#64748b', textAlign: 'center', marginTop: 20 },
  listContainer: { flexDirection: 'column' },
  userRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', backgroundColor: '#0f172a', padding: 12, borderRadius: 8, marginBottom: 10, borderWidth: 1, borderColor: '#334155' },
  userInfo: { flex: 1 },
  userIdText: { color: '#f8fafc', fontSize: 14, fontWeight: 'bold', letterSpacing: 1 },
  userCodeText: { color: '#94a3b8', fontSize: 12, marginTop: 4 },
  userExpiryText: { color: '#94a3b8', fontSize: 11 },
  textHighlight: { color: '#4ade80', fontWeight: 'bold' },
  textExpired: { color: '#f87171', fontWeight: 'bold' },
  actionRowContainer: { flexDirection: 'row', alignItems: 'center' },
  
  btnOpenDetail: { flexDirection: 'row', alignItems: 'center', paddingVertical: 6, paddingHorizontal: 12, backgroundColor: '#334155', borderRadius: 6, borderWidth: 1, borderColor: '#475569' },
  btnOpenDetailText: { fontSize: 11, fontWeight: 'bold', color: '#f8fafc', letterSpacing: 1 },
  
  modalOverlay: { flex: 1, backgroundColor: 'rgba(0, 0, 0, 0.7)', justifyContent: 'center', alignItems: 'center', padding: 16 },
  modalContent: { width: '100%', maxWidth: 400, backgroundColor: '#1e293b', borderRadius: 12, padding: 20, borderWidth: 1, borderColor: '#06b6d4', elevation: 10 },
  modalHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20, borderBottomWidth: 1, borderBottomColor: '#334155', paddingBottom: 10 },
  modalTitle: { color: '#22d3ee', fontSize: 18, fontWeight: 'bold' },
  closeModalBtn: { padding: 4 },
  modalInfoBox: { backgroundColor: '#0f172a', borderRadius: 8, padding: 16, marginBottom: 20, borderWidth: 1, borderColor: '#334155' },
  modalInfoRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 },
  modalLabel: { color: '#94a3b8', fontSize: 13 },
  modalValue: { color: '#f8fafc', fontSize: 14, fontWeight: 'bold' },
  
  correctionBox: { backgroundColor: 'rgba(234, 179, 8, 0.1)', padding: 16, borderRadius: 8, borderWidth: 1, borderColor: 'rgba(234, 179, 8, 0.3)', marginBottom: 20 },
  correctionTitle: { color: '#facc15', fontSize: 14, fontWeight: 'bold', marginBottom: 6 },
  correctionDesc: { color: '#cbd5e1', fontSize: 13, marginBottom: 4 },
  correctionDescSmall: { color: '#94a3b8', fontSize: 11, marginBottom: 12, fontStyle: 'italic' },
  correctionInputRow: { flexDirection: 'row', alignItems: 'center' },
  inputKoreksi: { flex: 1, backgroundColor: '#0f172a', color: '#fff', paddingHorizontal: 12, paddingVertical: 8, borderRadius: 6, borderWidth: 1, borderColor: '#475569', marginRight: 10 },
  btnUpdateKoreksi: { backgroundColor: '#ca8a04', paddingVertical: 10, paddingHorizontal: 16, borderRadius: 6 },
  btnUpdateKoreksiText: { color: '#fff', fontSize: 12, fontWeight: 'bold' },

  modalActionBox: { marginTop: 10 },
  modalActionBtn: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', paddingVertical: 12, borderRadius: 8, borderWidth: 1 },
  btnAktif: { backgroundColor: 'rgba(34, 197, 94, 0.1)', borderColor: 'rgba(34, 197, 94, 0.5)' },
  btnBanned: { backgroundColor: 'rgba(239, 68, 68, 0.1)', borderColor: 'rgba(239, 68, 68, 0.5)' },
  btnDeletePermanen: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', paddingVertical: 12, backgroundColor: '#ef4444', borderRadius: 8 },
  modalActionText: { color: '#f8fafc', fontSize: 13, fontWeight: 'bold', letterSpacing: 1 }
});
